(ns p79.crdt.space.planning
  (:require [p79.crdt.space.memory.planning :as planning])
  (:use clojure.test))

(deftest default-planner
  (is (= '[[?e :g ?v] (pos? ?v) [?e :a ?a] (pos? ?a) [?e :b 5 "tag"]]
        (#'planning/reorder-expression-clauses
          '[(pos? ?v)
            [?e :g ?v]
            (pos? ?a)
            [?e :a ?a]
            [?e :b 5 "tag"]]))))

(deftest predicate-expression-compilation
  (let [expr '(> 30 (inc ?x) ?y)
        fn (#'planning/expression-clause (#'planning/clause-bindings expr) expr)]
    (is (= {:code ''(fn [{:syms [?y ?x]}] (> 30 (inc ?x) ?y))
            :clause `'~expr}
          (meta fn)))
    (is (= false ((eval fn) '{?x 29 ?y 20})))
    (is (= true ((eval fn) '{?x 28 ?y 20})))))