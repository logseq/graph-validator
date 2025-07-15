(ns logseq.graph-validator.validations.class
  "Validations related to managing classes"
  (:require [clojure.test :refer [deftest is]]
            [logseq.graph-validator.state :as state]
            [logseq.db.file-based.rules :as file-rules]
            [datascript.core :as d]))

(defn- get-classes []
  (->> (d/q
        '[:find (pull ?b [*])
          :in $ %
          :where (page-property ?b :type "Class")]
        @state/db-conn
        [(:page-property file-rules/query-dsl-rules)])
       (map first)
       (map #(assoc (:block/properties %) :block/title (:block/title %)))))

(deftest classes-have-parents
  (is (empty? (remove #(or (some? (:parent %)) (= "Thing" (:block/title %)))
                      (get-classes)))
      "All classes have parent property except Thing"))

(deftest classes-have-urls
  (is (empty? (remove :url (get-classes)))
      "All classes have urls"))

(deftest classes-are-capitalized
  (is (empty? (remove #(re-find #"^[A-Z]" (:block/title %)) (get-classes)))
      "All classes start with a capital letter"))
