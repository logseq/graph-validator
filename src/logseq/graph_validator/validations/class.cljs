(ns logseq.graph-validator.validations.class
  "Validations related to managing classes"
  (:require [clojure.test :refer [deftest is]]
            [logseq.graph-validator.state :as state]
            [logseq.db.rules :as rules]
            [datascript.core :as d]))

(defn- get-classes []
  (->> (d/q
        '[:find (pull ?b [*])
          :in $ %
          :where (page-property ?b :type "Class")]
        @state/db-conn
        (vals rules/query-dsl-rules))
       (map first)
       (map #(assoc (:block/properties %) :block/original-name (:block/original-name %)))))

(deftest classes-have-parents
  (is (empty? (remove #(or (some? (:parent %)) (= "Thing" (:block/original-name %)))
                      (get-classes)))
      "All classes have parent property except Thing"))

(deftest classes-have-urls
  (is (empty? (remove :url (get-classes)))
      "All classes have urls"))

(deftest classes-are-capitalized
  (is (empty? (remove #(re-find #"^[A-Z]" (:block/original-name %)) (get-classes)))
      "All classes start with a capital letter"))
