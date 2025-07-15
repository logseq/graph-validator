(ns logseq.graph-validator
  "Github action that runs tests on a given graph directory"
  (:require [clojure.test :as t]
            [logseq.graph-parser.cli :as gp-cli]
            [logseq.graph-validator.state :as state]
            [logseq.graph-validator.config :as config]
            [logseq.graph-validator.default-validations]
            [clojure.edn :as edn]
            [clojure.string :as string]
            [babashka.cli :as cli]
            [promesa.core :as p]
            [nbb.classpath :as classpath]
            ["fs" :as fs]
            ["path" :as path]))

(defn- setup-graph [dir]
  (when-not (fs/existsSync dir)
    (println (str "Error: The directory '" dir "' does not exist."))
    (js/process.exit 1))
  (println "Parsing graph" dir)
  (reset! state/graph-dir dir)
  (let [{:keys [conn asts]} (gp-cli/parse-graph dir {:verbose false})]
    (reset! state/all-asts (mapcat :ast asts))
    ;; Gross but necessary to avoid double deref for every db fetch
    (set! state/db-conn conn)
    (println "Ast node count:" (count @state/all-asts))))

(defn- exclude-tests
  "Hacky way to exclude tests because t/run-tests doesn't give us test level control"
  [tests]
  (doseq [t tests]
    (let [[test-ns test-name]
          (if (string/includes? (str t) "/")
            (string/split t #"/") ["logseq.graph-validator.default-validations" t])]
      (when-let [var (get (ns-publics (symbol test-ns)) (symbol test-name))]
        (println "Excluded test" var)
        (alter-meta! var dissoc :test)))))

(def spec
  "Options spec"
  {:add-namespaces {:alias :a
                    :coerce []
                    :desc "Additional namespaces to test"}
   :directory {:desc "Graph directory to validate"
               :alias :d
               :default "."}
   :exclude {:alias :e
             :coerce []
             :desc "Specific tests to exclude"}
   :help {:alias :h
          :desc "Print help"}})

(defn- read-config [config]
  (try
    (edn/read-string config)
    (catch :default _
      (println "Error: Failed to parse config. Make sure it is valid EDN")
      (js/process.exit 1))))

(defn- get-validator-config [dir user-config]
  (merge-with (fn [v1 v2]
                (if (and (map? v1) (map? v2))
                  (merge v1 v2) v2))
              config/default-config
              (when (fs/existsSync (path/join dir ".graph-validator" "config.edn"))
                (read-config
                 (str (fs/readFileSync (path/join dir ".graph-validator" "config.edn")))))
              user-config))

(defn- run-tests [dir user-config]
  (let [;; Only allow non-empty options to not override .graph-validator/config.edn
        user-config' (into {} (keep (fn [[k v]] (when (seq v) [k v])) user-config))
        {:keys [exclude add-namespaces] :as config} (get-validator-config dir user-config')]
    (reset! state/config config)
    (when (seq add-namespaces)
      (classpath/add-classpath (path/join dir ".graph-validator")))
    (-> (p/do! (apply require (map symbol add-namespaces)))
        (p/then
         (fn [_promise-results]
           (when (seq exclude)
             (exclude-tests exclude))
           (setup-graph dir)
           (apply t/run-tests (into ['logseq.graph-validator.default-validations]
                                    (map symbol add-namespaces)))))
        (p/catch
         (fn [err]
           (prn :unexpected-failure! err)
           (js/process.exit 1))))))

(defn -main [& args]
  (let [options (-> (cli/parse-opts args {:spec spec})
                    ;; Handle empty collection values coming from action.yml
                    (update :exclude #(if (= ["logseq-graph-validator-empty"] %) [] %)))
        _ (when (:help options)
            (println (str "Usage: logseq-graph-validator [OPTIONS]\nOptions:\n"
                          (cli/format-opts {:spec spec})))
            (js/process.exit 1))
        ;; Debugging info for CI
        _ (when js/process.env.CI (println "Options:" (pr-str options)))
        ;; In CI, move up a directory since the script is run in subdirectory of
        ;; a project
        dir (if js/process.env.CI (path/join ".." (:directory options)) (:directory options))]
    (run-tests dir (select-keys options [:add-namespaces :exclude]))))

#js {:main -main}
