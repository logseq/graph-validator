(ns logseq.graph-validator.config
  "Graph-specific config to define for validator")

(def default-config
  "Provides defaults for config and lists all available config keys"
  {;; Additional namespaces to run under .graph-validator/
   :add-namespaces []
   ;; Exclude individual tests
   :exclude []})
