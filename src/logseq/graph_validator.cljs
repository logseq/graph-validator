(ns logseq.graph-validator
  "Github action that runs tests on a given graph directory"
  (:require [clojure.test :as t :refer [deftest is]]
            [datascript.core :as d]
            [logseq.graph-parser.cli :as gp-cli]
            [logseq.graph-parser.util.block-ref :as block-ref]
            [logseq.graph-validator.state :as state]
            [logseq.graph-validator.config :as config]
            [logseq.db.rules :as rules]
            [clojure.walk :as walk]
            [clojure.set :as set]
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

(defn- extract-subnodes-by-pred [pred node]
  (cond
    (= "Heading" (ffirst node))
    (filter pred (-> node first second :title))

    ;; E.g. for subnodes buried in Paragraph
    (vector? (-> node first second))
    (filter pred (-> node first second))))

(defn- ast->block-refs [ast]
  (->> ast
       (mapcat (partial extract-subnodes-by-pred
                        #(and (= "Link" (first %))
                              (= "Block_ref" (-> % second :url first)))))
       (map #(-> % second :url second))))

(defn- ast->embed-refs [ast]
  (->> ast
       (mapcat (partial extract-subnodes-by-pred
                        #(and (= "Macro" (first %))
                              (= "embed" (:name (second %)))
                              (block-ref/get-block-ref-id (str (first (:arguments (second %))))))))
       (map #(-> % second :arguments first block-ref/get-block-ref-id))))

(deftest block-refs-link-to-blocks-that-exist
  (let [block-refs (ast->block-refs @state/all-asts)]
    (println "Found" (count block-refs) "block refs")
    (is (empty?
         (set/difference
          (set block-refs)
          (->> (d/q '[:find (pull ?b [:block/properties])
                      :in $ %
                      :where (has-property ?b :id)]
                    @state/db-conn
                    (vals rules/query-dsl-rules))
               (map first)
               (map (comp :id :block/properties))
               set))))))

(deftest embed-block-refs-link-to-blocks-that-exist
  (let [embed-refs (ast->embed-refs @state/all-asts)]
    (println "Found" (count embed-refs) "embed block refs")
    (is (empty?
         (set/difference
          (set embed-refs)
          (->> (d/q '[:find (pull ?b [:block/properties])
                      :in $ %
                      :where (has-property ?b :id)]
                    @state/db-conn
                    (vals rules/query-dsl-rules))
               (map first)
               (map (comp :id :block/properties))
               set))))))

(defn- ast->queries
  [ast]
  (->> ast
       (mapcat (fn [nodes]
                 (keep
                  (fn [subnode]
                    (when (= ["Custom" "query"] (take 2 subnode))
                      (get subnode 4)))
                  nodes)))))

(deftest advanced-queries-have-valid-schema
  (let [query-strings (ast->queries @state/all-asts)]
    (println "Found" (count query-strings) "queries")
    (is (empty? (keep #(let [query (try (edn/read-string %)
                                     (catch :default _ nil))]
                         (when (nil? query) %))
                      query-strings))
        "Queries are valid EDN")

    (is (empty? (keep #(let [query (try (edn/read-string %)
                                     (catch :default _ nil))]
                         (when (not (contains? query :query)) %))
                      query-strings))
        "Queries have required :query key")))

(deftest invalid-properties-dont-exist
  (is (empty?
       (->> (d/q '[:find (pull ?b [*])
                   :in $
                   :where
                   [?b :block/properties]]
                 @state/db-conn)
            (map first)
            (filter #(seq (:block/invalid-properties %)))))))

(defn- ast->asset-links [ast]
  (->> ast
       (mapcat (partial extract-subnodes-by-pred
                        #(and (= "Link" (first %))
                              (= "Search" (-> % second :url first)))))
       (map #(-> % second :url second))
       (keep #(when (and (string? %) (= "assets" (path/basename (path/dirname %))))
                %))))

(deftest assets-exist-and-are-used
  (let [used-assets (set (map path/basename (ast->asset-links @state/all-asts)))
        all-assets (if (fs/existsSync (path/join @state/graph-dir "assets"))
                     (set (fs/readdirSync (path/join @state/graph-dir "assets")))
                     #{})]
    (println "Found" (count used-assets) "assets")
    (is (empty? (set/difference used-assets all-assets))
        "All used assets should exist")
    (is (empty? (set/difference all-assets used-assets))
        "All assets should be used")))

(defn- keep-for-ast [keep-node-fn nodes]
  (let [found (atom [])]
    (walk/postwalk
     (fn [elem]
       (when-let [saveable-val (and (vector? elem) (keep-node-fn elem))]
         (swap! found conj saveable-val))
       elem)
     nodes)
    @found))

(def ast->tag
  #(when (and (= "Tag" (first %)) (= "Plain" (-> % second first first)))
      (-> % second first second)))

(defn- ast->tags [nodes]
  (keep-for-ast ast->tag nodes))

(defn- ast->false-tags [nodes]
  (keep-for-ast
   (fn [node]
     (cond
       ;; Pull out background-color properties
       (= "Property_Drawer" (first node))
       (->> (second node)
            (filter #(#{"background-color"} (first %)))
            (mapcat #(get % 2))
            (keep ast->tag))

       ;; Pull out tags in advanced queries
       (= ["Custom" "query"] (take 2 node))
       (when (= "Paragraph" (ffirst (get node 3)))
         (->> (get node 3)
              first
              second
              (keep ast->tag)))))

   nodes))

(defn- ast->page-refs [nodes]
  (keep-for-ast
   #(when (and (= "Link" (first %)) (= "Page_ref" (-> % second :url first)))
      (-> % second :url second))
   nodes))

(defn- get-all-aliases
  [db]
  (->> (d/q '[:find (pull ?b [:block/properties])
                            :in $ %
                            :where
                            (has-page-property ?b :alias)]
                          db
                          (vals rules/query-dsl-rules))
                     (map first)
                     (map (comp :alias :block/properties))
                     (mapcat identity)
                     (map string/lower-case)
                     set))

(deftest tags-and-page-refs-have-pages
  (let [used-tags* (set (map (comp string/lower-case path/basename) (ast->tags @state/all-asts)))
        false-used-tags (mapcat identity (ast->false-tags @state/all-asts))
        used-tags (apply disj used-tags* false-used-tags)
        used-page-refs* (set (map string/lower-case (ast->page-refs @state/all-asts)))
        ;; TODO: Add more thorough version with gp-config/get-date-formatter as needed
        used-page-refs (set (remove #(re-find #"^(jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)\s+" %)
                                    used-page-refs*))
        aliases (get-all-aliases @state/db-conn)
        all-pages* (if (fs/existsSync (path/join @state/graph-dir "pages"))
                     (->> (fs/readdirSync (path/join @state/graph-dir "pages"))
                          ;; strip extension if there is one
                          (map #(or (second (re-find #"(.*)(?:\.[^.]+)$" %))
                                    %))
                          (map string/lower-case)
                          (map #(string/replace % "___" "/"))
                          set)
                     #{})
        all-pages (into all-pages* aliases)]
    (println "Found" (count used-tags) "tags")
    (println "Found" (count used-page-refs) "page refs")
    (is (empty? (set/difference used-tags all-pages))
        "All used tags should have pages")

    (is (empty? (set/difference used-page-refs all-pages))
        "All used page refs should have pages")))

(defn- exclude-tests
  "Hacky way to exclude tests because t/run-tests doesn't give us test level control"
  [tests]
  (doseq [t tests]
    (when-let [var (get (ns-publics 'logseq.graph-validator) (symbol t))]
      (println "Excluded test" var)
      (alter-meta! var dissoc :test))))

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
        {:keys [exclude add-namespaces]} (get-validator-config dir user-config')]
    (when (seq exclude)
      (exclude-tests exclude))
    (when (seq add-namespaces)
      (classpath/add-classpath (path/join dir ".graph-validator")))
    (-> (p/all (map #(require (symbol %)) add-namespaces))
        (p/then
         (fn [_promise-results]
           (setup-graph dir)
           (apply t/run-tests (into ['logseq.graph-validator]
                                    (map symbol add-namespaces))))))))

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
