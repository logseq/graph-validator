(ns logseq.graph-validator.validations.property
  "This ns manages all user properties. Validations ensure the following about a graph:
  * Each property has a page with required url and rangeIncludes properties.
    domainIncludes and unique are optional properties. To ignore certain
    properties having pages and thus validations, add them to the config :property/ignore-list. 
  * Property names start with a lower case letter
  * The rangeIncludes property is a list of values a property can have. They can be
    a descendant of Class or DataType.
    * If a Class descendant, then the value is valid if its a page with a type property that is the
      rangeIncludes class or a descendant of the rangeIncludes class
    * If a DataType descendant, then the value must be that specific DataType as
      determined by its validator function. The following datatypes are already
      defined: Integer, String, Boolean, Date, Time, Uri, Integer, Float and
      StringWithRefs. Custom data types can be defined by creating a page for them
      and then adding a validation fn for them in :property/data-type-validations.
  * A property can have an optional domainIncludes property. This property is a
    list of classes that would use the property.
  * If a property has the :unique property set to true, a validation is run to ensure every
    property value is unique
   
   These validations rely on the following special property and class names:
  * Classes and properties are pages and they inherit from a `Class` page and a `Property` page
  * Pages have classes through a `type` property. Classes can have subclasses through the `parent` property
  * A property's range and domain values are specified through `domainIncludes` and `rangeIncludes` properties
  * Property values that are literal values are instances of subclasses to the `DataType` class."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [clojure.set :as set]
            [clojure.string :as string]
            [logseq.graph-validator.state :as state]
            [logseq.graph-parser.property :as gp-property]
            [logseq.graph-parser.util.page-ref :as page-ref]
            [logseq.graph-parser.util :as gp-util]
            [logseq.db.rules :as rules]
            [datascript.core :as d]
            [clojure.edn :as edn]
            ["path" :as path]
            ["fs" :as fs]))

(def logseq-config (atom nil))

;; Copied from graph-validator for now
(defn- read-config [config]
  (try
    (edn/read-string config)
    (catch :default _
      (println "Error: Failed to parse config. Make sure it is valid EDN")
      (js/process.exit 1))))

(use-fixtures
  :once
  (fn [f]
    (reset! logseq-config
            (read-config
             (str (fs/readFileSync (path/join @state/graph-dir "logseq" "config.edn")))))
    (f)))

;; Db util fns
;; ===========
(defn- get-property-text-values-for-property
  "Get property values for property from :block/properties-text-values"
  [db property]
  (let [pred (fn [_db properties]
               (get properties property))]
    (->>
     (d/q
      '[:find ?property-val
        :in $ ?pred
        :where
        [?b :block/properties-text-values ?p]
        [(?pred $ ?p) ?property-val]]
      db
      pred))))

(defn- get-all-properties
  "Get all unique property names"
  [db]
  (let [properties (d/q
                    '[:find [?p ...]
                      :where
                      [_ :block/properties ?p]]
                    db)]
    (->> (map keys properties)
         (apply concat)
         distinct
         set)))

(defn- get-all-property-values
  "Get all property values from :properties"
  [db]
  (->> (d/q
        '[:find ?p
          :in $
          :where
          [?b :block/properties ?p]
          [(missing? $ ?b :block/pre-block?)]]
        db)
       (map first)))

(defn- get-all-property-entities
  "Get all property entities from :properties"
  [db]
  (d/q
   '[:find (pull ?b [:block/content :block/original-name :block/properties]) ?p
     :in $
     :where
     [?b :block/properties ?p]
     [(missing? $ ?b :block/pre-block?)]]
   db))

(defn- get-all-property-text-values
  "Get all property values from :properties-text-values"
  [db]
  (->> (d/q
        '[:find ?p
          :in $
          :where
          [?b :block/properties-text-values ?p]
          [(missing? $ ?b :block/pre-block?)]]
        db)
       (map first)))

(defn- get-all-user-properties
  [db]
  (let [built-in-properties (set/union (gp-property/hidden-built-in-properties)
                                       (gp-property/editable-built-in-properties)
                                       ;; Copied properties for namespaces that can't be loaded :(
                                       #{:card-last-interval :card-repeats :card-last-reviewed
                                         :card-next-schedule :card-last-score :card-ease-factor
                                         :background-image})]
    (set/difference (get-all-properties db)
                    built-in-properties)))

(defn- get-all-page-things
  []
  (->> (d/q '[:find (pull ?b [*])
              :in $ %
              :where [?b :block/original-name]
              (has-page-property ?b :type)]
            @state/db-conn
            (vals rules/query-dsl-rules))
       (map first)))

;; Macro fns
;; =========
(defn- macro-subs
  [macro-content arguments]
  (loop [s macro-content
         args arguments
         n 1]
    (if (seq args)
      (recur
       (string/replace s (str "$" n) (first args))
       (rest args)
       (inc n))
      s)))

(defn- macro-expand-value
  "Checks each value for a macro and expands it if there's a logseq config for it"
  [val logseq-config]
  (if-let [[_ macro args] (and (string? val)
                               (seq (re-matches #"\{\{(\S+)\s+(.*)\}\}" val)))]
    (if-let [content (get-in logseq-config [:macros macro])]
      (macro-subs content (string/split args #"\s+"))
      val)
    val))

;; Other util fns
;; ==============
(defn- validate-property-only-has-not-refs
  "This is for properties that have strings with refs in them. Can't validate
  them as strings since they may have refs so have to look at raw values and at
  least confirm they aren't just a ref"
  [property]
  (is (empty?
       (remove #(and (not (page-ref/page-ref? %))
                     (not (re-matches #"#\S+" %)))
               (map first (get-property-text-values-for-property @state/db-conn property))))
      (str "All values for " property " have correct data type")))

(def preds
  {:Integer int?
   :String string?
   :Boolean boolean?
   :Uri gp-util/url?
   ;; TODO: Make this configurable
   :Date #(re-matches #"\d{2}-\d{2}-\d{4}" %)
   :Time #(re-matches #"\d\d?:\d\d" %)
   ;; Have to convert decimal for now b/c of inconsistent int parsing
   :Float #(if (string? %) (float? (parse-double %)) (float? %))})

(defn- get-preds*
  []
  (merge preds
         (update-vals (:property/data-type-validations @state/config)
                      (fn [f]
                        (fn [x] (eval (list f x)))))))

(def get-preds (memoize get-preds*))

(defn- validate-data-type
  [property {:keys [data-type]} property-values]
  (if (= :StringWithRefs data-type)
    (validate-property-only-has-not-refs property)
    (if-let [pred (get (get-preds) data-type)]
      (do
        (assert (pos? (count (get property-values property)))
                (str "Property " property " must have at least one value"))
        (is (empty?
             (remove pred (map #(macro-expand-value % @logseq-config)
                               (get property-values property))))
            (str "All values for " property " have data type " data-type)))
      (throw (ex-info (str "No data-type definition for " data-type) {})))))

;; Tests
;; =====
(deftest properties-with-data-types-have-valid-ranges
  (let [all-things (get-all-page-things)
        children-maps (->> all-things
                           (filter #(= "Class" (first (get-in % [:block/properties :type]))))
                           (map #(vector (:block/original-name %) (first (get-in % [:block/properties :parent]))))
                           (into {}))
        range-properties (->> all-things
                              (filter #(= "Property" (first (get-in % [:block/properties :type]))))
                              (keep #(when-let [ranges (get-in % [:block/properties :rangeincludes])]
                                       (vector (keyword (:block/name %)) ranges)))
                              (filter #(contains? (set (map children-maps (second %))) "DataType"))
                              (into {}))
        _ (println "Validating ranges with data types for" (count range-properties) "properties")
        property-values (->> (get-all-property-values @state/db-conn)
                             (mapcat identity)
                             (reduce (fn [m [k v]] (update m k (fnil conj #{}) v)) {}))]
    (doseq [[property ranges] range-properties]
      ;; later: support multiple data-types
      (validate-data-type property {:data-type (keyword (first ranges))} property-values))))

(defn- is-ancestor? [children-maps parent-classes child-class]
  (seq
   (set/intersection
    (loop [ancestors #{}
           current-thing child-class]
      (if-let [parent (children-maps current-thing)]
        (recur (conj ancestors parent) parent)
        ancestors))
    parent-classes)))

(defn- validate-property-ranges
  [property ranges property-values page-classes children-maps]
  (let [property-values-to-classes (select-keys page-classes (map first property-values))]
    (is (empty? (->> property-values-to-classes
                     (remove #(contains? ranges (val %)))
                     (remove #(is-ancestor? children-maps ranges (val %)))))
        (str "All property values for " property " must be one of " ranges))))

(deftest properties-with-refs-have-valid-ranges
  (let [all-things (get-all-page-things)
        children-maps (->> all-things
                           (filter #(= "Class" (first (get-in % [:block/properties :type]))))
                           (map #(vector (:block/original-name %) (first (get-in % [:block/properties :parent]))))
                           (into {}))
        range-properties (->> all-things
                              (filter #(= "Property" (first (get-in % [:block/properties :type]))))
                              (keep #(when-let [ranges (get-in % [:block/properties :rangeincludes])]
                                       (vector (keyword (:block/name %)) ranges)))
                              (filter #(not (contains? (set (map children-maps (second %))) "DataType")))
                              (into {}))
        _ (println "Validating ranges with entities for" (count range-properties) "properties")
        property-values (->> (get-all-property-values @state/db-conn)
                             (mapcat identity)
                             (reduce (fn [m [k v]] (update m k (fnil conj #{}) v)) {}))
        page-classes (->> all-things
                          (mapcat (fn [b]
                                    (map #(vector % (first (get-in b [:block/properties :type])))
                                         (into [(:block/original-name b)]
                                               (get-in b [:block/properties :alias])))))
                          (into {}))]
    (doseq [[property ranges] range-properties]
      (validate-property-ranges property ranges (property-values property) page-classes children-maps))))

(defn- validate-property-domains
  [property domains property-ents-to-classes children-maps]
  (is (empty? (->> property-ents-to-classes
                   (remove #(contains? domains (val %)))
                   (remove #(is-ancestor? children-maps domains (val %)))))
      (str "All property entities for " property " must be one of " domains)))

(deftest properties-with-refs-have-valid-domains
  (let [all-things (get-all-page-things) 
        domain-properties (->> all-things
                               (filter #(= "Property" (first (get-in % [:block/properties :type]))))
                               (keep #(when-let [domains (get-in % [:block/properties :domainincludes])]
                                        (vector (keyword (:block/name %)) domains)))
                               (into {}))
        property-ents (->> (get-all-property-entities @state/db-conn)
                           (mapcat (fn [[ent props]]
                                     (map #(vector (first %)
                                                   (-> ent
                                                       (dissoc :block/properties)
                                                       (assoc :types (get-in ent [:block/properties :type]))))
                                          props)))
                           (reduce (fn [m [prop ent]]
                                     (assoc-in m
                                               [prop (select-keys ent [:block/content :block/original-name])]
                                               ;; later: Support multiple types
                                               (first (:types ent))))
                                   {}))
        _ (println "Validating domains for" (count domain-properties) "properties")
        children-maps (->> all-things
                           (filter #(= "Class" (first (get-in % [:block/properties :type]))))
                           (map #(vector (:block/original-name %) (first (get-in % [:block/properties :parent]))))
                           (into {}))]
    (doseq [[property domains] domain-properties]
      (validate-property-domains property domains (property-ents property) children-maps))))

(defn- get-property-pages []
  (->> (d/q
        '[:find (pull ?b [*])
          :in $ %
          :where (page-property ?b :type "Property")]
        @state/db-conn
        (vals rules/query-dsl-rules))
       (map first)
       (map #(assoc (:block/properties %) :block/name (:block/name %)))))

(deftest properties-have-property-pages
  (is (empty?
       (set/difference (set (map name (get-all-user-properties @state/db-conn)))
                       (set (map :block/name (get-property-pages)))
                       (set (map name (:property/ignore-list @state/config)))))))

(deftest properties-have-urls
  (is (empty? (remove :url (get-property-pages)))
      "All properties have urls"))

(deftest properties-have-ranges
  (is (empty? (remove :rangeincludes (get-property-pages)))
      "All properties have rangeIncludes"))

(deftest properties-are-lower-cased
  (is (empty? (remove #(re-find #"^[a-z]" (:block/name %)) (get-property-pages)))
      "All properties start with a lowercase letter"))

(deftest unique-properties-are-unique-across-graph
  (let [all-things (get-all-page-things)
        unique-properties (->> all-things
                               (filter #(= "Property" (first (get-in % [:block/properties :type]))))
                               (filter #(get-in % [:block/properties :unique])))]
    (println "Validating uniqueness for" (count unique-properties) "properties")
    (doseq [{property :block/name} unique-properties]
      (is (empty?
           (->> (get-all-property-text-values @state/db-conn)
                (map #((keyword property) %))
                frequencies
                ((fn [x] (dissoc x nil)))
                (filter #(> (val %) 1))
                (into {})))))))
