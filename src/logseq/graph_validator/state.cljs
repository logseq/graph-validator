(ns logseq.graph-validator.state
  "State that is globally accessible to all tests")

(def db-conn "Datascript conn return from parsed graph" nil)
(def all-asts "Vec of ast maps returned from a parsed graph" (atom nil))
(def graph-dir "Directory of given graph" (atom nil))
(def config "Graph's current config" (atom nil))