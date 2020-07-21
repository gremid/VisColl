(ns viscoll.formula2tei
  "Parse Walters Art Museum-style collation formulas and emit TEI/XML P5."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.data.xml :as xml]
            [clojure.test :refer :all]
            [clojure.stacktrace :refer [print-stack-trace]]
            [clojure.walk :refer [postwalk]]
            [instaparse.core :as parse]))

;; ## Syntax
;;
;; Walters-style collation formulae have a form like this:
;;
;; i, 1(8,-1,2), 2(6), 3(10,-1,9), 4(10,-4,8), 5(6,-1,5), 6(6), 7-11(8),
;; 12(8,-8), 13(8), 14(6), 15(8,-8), 16(12,-5,9,12), 17(10,-6,8,10),
;; 18(10,-6,8,10) 19(8,-7), 20-21(8), i

;; Here the leading and trailing 'i' indicate a count of flyleaves. Before each
;; parenthetical unit - e.g., '1(8,-1,2)' - is a single quire number (e.g., 1,
;; 2, 3) or a range of quire numbers (e.g., 7-11, 20-21). Within each
;; parenthetical set - e.g., (8,-1,2) - the first number indicates the number of
;; leaves in a theoretical regular quire structure, that would apply if this
;; quire were regular: 8 leaves for a regular quire of four bifolia or 6 leaves
;; for a quire of three bifolia. The leaf number is then followed by a series of
;; subtracted positions that explain how the regular quire structure should be
;; altered to derive the structure of the quire in its current form.
;;
;; The general form of the formula is:
;;
;; QUIRE_NO[-QUIRE_NO](LEAF_COUNT[,-POSITION[,POSITION,..]])
;;
;; For example, '1(8,-1,2)' describes a quire of 6 extant leaves. The quire has
;; two bifolia followed by two singletons. The two bifolia are positions 3+6,
;; 4+5, followed by singletons at positions 7, 8. The positions needed to
;; complete the structure are the missing positions 1* and 2* (here marked with
;; a * to indicate their absence).
;;
;;  _ _ _ _ _ _ _ _ 1*
;; |  _ _ _ _ _ _ _ 2*
;; | |  ___________ 3
;; | | |  _________ 4
;; | | | |
;; | | | |
;; | | | |_________ 5
;; | | |___________ 6
;; | |_____________ 7
;; |_______________ 8
;;
;; NB The numbers here indicate *theoretical* leaf positions, not folio numbers.
;;
;; NB Also, these formulae do no describe how the quire came to be, but rather
;; merely describe the structure in a subtractive formula. Nothing should be
;; inferred about the history of the quire from this formula. In the example
;; above, the quire may have been a quire of 4 bifolia to which the last two
;; singletons were later added; the formula is not concerned with this.

(def ^:private parser
  (parse/parser (io/resource "viscoll/walters-formula.bnf")))

(defn transform-leaves
  [leaves substract]
  (let [top (first leaves)
        bottom (last leaves)
        inner (some->> leaves (drop 1) (butlast))]
    (merge  (when-not (substract top) {:top top})
            (when inner {:inner (transform-leaves inner substract)})
            (when-not (substract bottom) {:bottom bottom}))))

(defn transform-quire
  [{:keys [leaves substract] :or {substract #{}}}]
  (transform-leaves (range 1 (inc leaves)) substract))

(defn transform-ast
  [v]
  (cond
    (string? v) (Integer/parseInt ^String v)
    (vector? v) (let [[k & args] v]
                  (condp = k
                    :flyleaf (list {:leaves {:top 1}})
                    :substract {:substract (into #{} args)}
                    :leaves {:leaves (first args)}
                    :id {:id (let [[a1 a2] args] (range a1 (inc (or a2 a1))))}
                    :quire (let [{id :id :as args} (apply merge args)
                                 quire {:leaves (transform-quire args)}]
                             (map (partial assoc quire :id) id))
                    :formula (apply concat args)
                    v))
    :else v))

(defn parse
  "Parses collation formula strings into ASTs, throwing an exception on failure."
  [s]
  (let [ast (parser (str/trim s))]
    (if (vector? ast)
      (postwalk transform-ast ast)
      (throw (ex-info s {::parse ast})))))

(deftest parsing
  (testing "Walters Art Museum-style collation formula parser"
    (is (->
         (str "i, 1(8,-1,2), 2(6), 3(10,-1,9), 4(10,-4,8), 5(6,-1,5), 6(6), "
              "7-11(8), 12(8,-8), 13(8), 14(6), 15(8,-8), 16(12,-5,9,12), "
              "17(10,-6,8,10), 18(10,-6,8,10), 19(8,-7), 20-21(8), i")
         (parse)
         (sequential?)))))

(defn page-numbers
  []
  (for [num (iterate inc 1) page ["r" "v"]]
    (format "%05d%s" num page)))

(comment
  (take 10 (page-numbers)))

(xml/alias-uri :tei "http://www.tei-c.org/ns/1.0")

(defn leaves->tei-list
  [{:keys [top inner bottom]}]
  [::tei/list {:type "leaf"}
   (concat
    (when top [[::tei/item {:n top}]])
    (when inner [[::tei/item (leaves->tei-list inner)]])
    (when bottom [[::tei/item {:n bottom}]]))])

(defn quires->tei-list
  [quires]
  [::tei/list {:type "quire"}
   (for [{id :id leaves :leaves} quires]
     [::tei/item (if id {:n id} {})
      (leaves->tei-list leaves)])])

(defn -main
  [& args]
  (try
    (let [formula (str/join ", " args)
          quires (parse formula)]
      (-> [::tei/TEI {:xmlns "http://www.tei-c.org/ns/1.0"}
           [::tei/teiHeader
            [::tei/fileDesc
             [::tei/titleStmt
              [::tei/title formula]]
             [::tei/publicationStmt [::tei/p]]
             [::tei/sourceDesc [::tei/p]]]]
           [::tei/text
            [::tei/body
             [::tei/listBibl
              [::tei/msDesc
               [::tei/msIdentifier]
               [::tei/physDesc
                [::tei/objectDesc
                 [::tei/supportDesc
                  [::tei/collation
                   [::tei/formula formula]
                   (-> formula parse quires->tei-list)]]]]]]]]]
          (xml/sexp-as-element)
          (xml/emit *out* :encoding "UTF-8"))
      (flush))
    (System/exit 0)
    (catch Throwable t
        (do
          (if-let [parse-error (some-> t ex-data ::parse)]
            (println parse-error)
            (print-stack-trace t))
          (System/exit 1)))))

