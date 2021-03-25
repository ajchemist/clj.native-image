(ns clj.native-image
  "Builds GraalVM native images from deps.edn projects."
  (:require
   [clojure.java.io :as jio]
   [clojure.string :as cs]
   [clojure.tools.deps.alpha :as deps]
   [clojure.tools.deps.alpha.util.dir :as deps.dir]
   [clojure.tools.deps.alpha.util.maven :as deps.maven]
   )
  (:import
   java.io.BufferedReader
   java.io.File
   ))


(set! *warn-on-reflection* true)


(def ^:dynamic *level*
  (let [level (keyword (System/getProperty "clj.native-image.log.level"))]
    (or (#{:debug :info :warn :error} (keyword level)) :debug))) ;


;;


(defn project-deps
  [deps-file]
  (let [{:keys [project-edn]} (deps/find-edn-maps deps-file)]
    (deps/merge-edns [{:mvn/repos deps.maven/standard-repos} project-edn])))


(defn make-classpath
  "Better than (System/getProperty \"java.class.path\")"
  [deps-file alias-kws]
  (let [deps     (project-deps deps-file)
        args-map (deps/combine-aliases deps alias-kws)]
    (-> (deps/calc-basis deps {:resolve-args args-map :classpath-args args-map})
      :classpath-roots deps/join-classpath)))


(defn clean
  [dir]
  (let [target-dir (jio/file dir)]
    (when (#{:debug :info} *level*)
      (println "Cleaning" (str target-dir)))
    (run!
      (fn [file]
        (jio/delete-file file))
      (-> target-dir (file-seq) (rest) (reverse)))
    (.mkdir target-dir)))


(defn sh
  "Launches a process with optional args, returning exit code.
  Prints stdout & stderr."
  [bin & args]
  (let [^"[Ljava.lang.String;" arg-array (into-array String (cons bin args))
        process                          (-> (ProcessBuilder. arg-array)
                                           (.redirectErrorStream true) ;; TODO stream stderr to stderr
                                           (.start))]
    (with-open [out (jio/reader (.getInputStream process))]
      (loop []
        (when-let [line (.readLine ^BufferedReader out)]
          (println line)
          (recur))))
    (.waitFor process)))


;;


(def windows? (cs/starts-with? (System/getProperty "os.name") "Windows"))


(defn exec-native-image
  "Executes native-image (bin) with opts, specifying a classpath,
   main/entrypoint class, and destination path."
  [bin opts cp main]
  (let [cli-args (cond-> []
                   (seq opts)     (into opts)
                   cp             (into ["-cp" cp])
                   main           (conj main)
                   ;; apparently native-image --no-server isn't currently supported on Windows
                   (not windows?) (conj "--no-server"))]
    (apply sh bin cli-args)))


(defn native-image-bin-path
  []
  (let [graal-paths [(str (System/getenv "GRAALVM_HOME") "/bin")
                     (System/getenv "GRAALVM_HOME")]
        paths       (lazy-cat graal-paths (cs/split (System/getenv "PATH") (re-pattern (File/pathSeparator))))
        filename    (cond-> "native-image" windows? (str ".cmd"))]
    (first
      (for [path  (distinct paths)
            :let  [file (jio/file path filename)]
            :when (.exists file)]
        (.getAbsolutePath file)))))


(defn native-image-classpath
  "Returns classpath string for `deps-file` with alias-kws."
  []
  (as-> (System/getProperty "java.class.path") $
    (cs/split $ (re-pattern (str File/pathSeparatorChar)))
    (remove #(cs/includes? "clj.native-image" %) $) ;; exclude ourselves
    (cs/join File/pathSeparatorChar $)))


(defn build
  [main-ns opts]
  (let [[nat-img-path & nat-img-opts]
        (if (some-> (first opts) (jio/file) (.exists)) ;; check first arg is file path
          opts
          (cons (native-image-bin-path) opts))]
    (when-not nat-img-path
      (binding [*out* *err*]
        (println "Could not find GraalVM's native-image!")
        (println "Please make sure that the environment variable $GRAALVM_HOME is set")
        (println "The native-image tool must also be installed ($GRAALVM_HOME/bin/gu install native-image)")
        (println "If you do not wish to set the GRAALVM_HOME environment variable,")
        (println "you may pass the path to native-image as the second argument to clj.native-image"))
      (System/exit 1))
    (when-not (string? main-ns)
      (binding [*out* *err*] (println "Main namespace required e.g. \"script\" if main file is ./script.clj"))
      (System/exit 1))

    (System/exit
      (exec-native-image
        nat-img-path
        nat-img-opts
        (native-image-classpath)
        (munge main-ns)))))


(defn build-x
  [{:keys [deps-file aliases main libs compile-path bin args]}]
  (let [deps-dir     (if deps-file
                       (-> (jio/file deps-file) (.getCanonicalFile) (.getParentFile))
                       deps.dir/*the-dir*)
        compile-path (or compile-path *compile-path*)
        bin          (or bin (native-image-bin-path))
        classpath    (make-classpath deps-file aliases)]
    (deps.dir/with-dir deps-dir
      (clean compile-path)
      (when-not (empty? libs)
        (when (#{:debug :info} *level*) (println "Compiling Libs"))
        (run! #(compile (symbol %)) libs))
      (when (#{:debug :info} *level*) (println "Compiling" main))
      (compile (symbol main))
      (when-not bin
        (binding [*out* *err*]
          (println "Could not find GraalVM's native-image!")
          (println "Please make sure that the environment variable $GRAALVM_HOME is set")
          (println "The native-image tool must also be installed ($GRAALVM_HOME/bin/gu install native-image)")
          (println "If you do not wish to set the GRAALVM_HOME environment variable,")
          (println "you may pass the path to native-image as the second argument to clj.native-image"))
        (System/exit 1))
      (when-not (string? main)
        (binding [*out* *err*]
          (println "Main namespace required e.g. \"script\" if main file is ./script.clj"))
        (System/exit 1))
      (when (#{:debug} *level*)
        (println "-cp" classpath))
      (System/exit
        (exec-native-image
          bin
          args
          classpath
          (str (munge main)))))))


(defn -main
  [main-ns & args]
  (try
    (build main-ns args)
    (finally
      (shutdown-agents))))


(set! *warn-on-reflection* false)
