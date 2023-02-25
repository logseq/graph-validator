(ns action
  "Github action that runs tests on a given graph directory"
  (:require [clojure.test :as t :refer [deftest is]]
            [datascript.core :as d]
            [logseq.graph-parser.cli :as gp-cli]
            [logseq.graph-parser.util.block-ref :as block-ref]
            [logseq.db.rules :as rules]
            [clojure.walk :as walk]
            [clojure.set :as set]
            [clojure.edn :as edn]
            [clojure.string :as string]
            [babashka.cli :as cli]
            ["fs" :as fs]
            ["path" :as path]))

(def db-conn (atom nil))
(def all-asts (atom nil))
(def graph-dir (atom nil))

(defn- setup-graph [dir]
  (println "Parsing graph" dir)
  (reset! graph-dir dir)
  (let [{:keys [conn asts]} (gp-cli/parse-graph dir {:verbose false})]
    (reset! db-conn conn)
    (reset! all-asts (mapcat :ast asts))
    (println "Ast node count:" (count @all-asts))))

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
  (let [block-refs (ast->block-refs @all-asts)]
    (println "Found" (count block-refs) "block refs")
    (is (empty?
         (set/difference
          (set block-refs)
          (->> (d/q '[:find (pull ?b [:block/properties])
                      :in $ %
                      :where (has-property ?b :id)]
                    @@db-conn
                    (vals rules/query-dsl-rules))
               (map first)
               (map (comp :id :block/properties))
               set))))))

(deftest embed-block-refs-link-to-blocks-that-exist
  (let [embed-refs (ast->embed-refs @all-asts)]
    (println "Found" (count embed-refs) "embed block refs")
    (is (empty?
         (set/difference
          (set embed-refs)
          (->> (d/q '[:find (pull ?b [:block/properties])
                      :in $ %
                      :where (has-property ?b :id)]
                    @@db-conn
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
  (let [query-strings (ast->queries @all-asts)]
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
                 @@db-conn)
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
  (let [used-assets (set (map path/basename (ast->asset-links @all-asts)))
        all-assets (if (fs/existsSync (path/join @graph-dir "assets"))
                     (set (fs/readdirSync (path/join @graph-dir "assets")))
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
  (let [used-tags* (set (map (comp string/lower-case path/basename) (ast->tags @all-asts)))
        false-used-tags (mapcat identity (ast->false-tags @all-asts))
        used-tags (apply disj used-tags* false-used-tags)
        used-page-refs* (set (map string/lower-case (ast->page-refs @all-asts)))
        ;; TODO: Add more thorough version with gp-config/get-date-formatter as needed
        used-page-refs (set (remove #(re-find #"^(jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)\s+" %)
                                    used-page-refs*))
        aliases (get-all-aliases @@db-conn)
        all-pages* (if (fs/existsSync (path/join @graph-dir "pages"))
                     (->> (fs/readdirSync (path/join @graph-dir "pages"))
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
    (when-let [var (get (ns-publics 'action) (symbol t))]
      (println "Excluded test" var)
      (alter-meta! var dissoc :test))))

;; run this function with: nbb-logseq -m action/run-tests
(defn run-tests [& *args]
  (let [args (js->clj *args)
        dir* (or (first args) ".")
        options (update (cli/parse-opts (rest args) {:coerce {:exclude []}})
                        :exclude
                        ;; Handle edge cases where exclude should be empty when
                        ;; `--exclude ''` or `--exclude` b/c of action.yml
                        #(if (#{[true] [""]} %) [] %))
        _ (println "Options:" (pr-str options))
        ;; Move up a directory since the script is run in subdirectory of a
        ;; project
        dir (if (path/isAbsolute dir*) dir* (path/join ".." dir*))]
    (when (seq (:exclude options))
      (exclude-tests (:exclude options)))
    (setup-graph dir)
    (t/run-tests 'action)))

#js {:main run-tests}
