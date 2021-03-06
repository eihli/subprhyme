(ns com.owoga.prhyme.nlg.prhyme-nlg
  (:require [clojure.zip :as zip]
            [clojure.string :as string]
            [taoensso.timbre :as timbre]
            [com.owoga.prhyme.util.math :as math]
            [com.owoga.phonetics.syllabify :as owoga.syllabify]
            [com.owoga.phonetics :as owoga.phonetics]
            [com.owoga.tightly-packed-trie.encoding :as encoding]
            [examples.core :as examples]
            [taoensso.nippy :as nippy]
            [com.owoga.prhyme.nlp.core :as nlp]
            [examples.tpt :as examples.tpt]
            [clojure.java.io :as io]
            [com.owoga.prhyme.data-transform :as df]
            [com.owoga.trie :as trie]
            [com.owoga.prhyme.util.weighted-rand :as weighted-rand]
            [clojure.set :as set]
            [com.owoga.tightly-packed-trie :as tpt]
            [com.owoga.prhyme.nlp.tag-sets.treebank-ii :as tb2]))

(defn update-values [m f & args]
  (reduce
   (fn [acc [k v]]
     (assoc acc k (apply f v args)))
   {}
   m))

(defn generate
  [pos-path->word-freqs
   pos->word-freqs
   target-parse-tree]
  (loop [parse-zipper (zip/seq-zip target-parse-tree)]
    (cond
      (zip/end? parse-zipper) (zip/root parse-zipper)

      (zip/branch? parse-zipper)
      (recur (zip/next parse-zipper))

      (string? (zip/node parse-zipper))
      (recur (zip/next parse-zipper))

      (and (symbol? (zip/node parse-zipper))
           (or (pos->word-freqs (zip/node parse-zipper))
               (pos-path->word-freqs (seq (map first (zip/path parse-zipper))))))
      (let [target-path (seq (map first (zip/path parse-zipper)))
            target-pos (zip/node parse-zipper)
            pos-path-word (pos-path->word-freqs target-path)
            pos-word (pos->word-freqs target-pos)]
        (timbre/info "Choosing POS for" target-path)
        (let [selection (weighted-rand/weighted-selection-from-map
                         (merge-with +
                          (update-values pos-path-word * 2)
                          pos-word))]
          (timbre/info "from" (take 5
                                    (merge-with +
                                     (update-values pos-path-word * 2)
                                     pos-word)))
          (timbre/info "Chose " selection)
          (recur
           (-> parse-zipper
               zip/up
               (#(zip/replace % (list (zip/node (zip/down %)) selection)))
               zip/next
               zip/next))))

      :else
      (recur (zip/next parse-zipper)))))

(defn next-word [zipper]
  (->> zipper
      nlp/iter-zip
      (filter #(string? (zip/node %)))
      first
      (#(if (nil? %) :end (zip/node %)))))

(defn next-two-words [nodes]
  (timbre/info
   (apply list (->> nodes
                    (map zip/node))))
  (->> nodes
       (filter #(string? (zip/node %)))
       (take 2)
       (map #(if (nil? %) :end (zip/node %)))))

(comment
  (let [zipper (zip/seq-zip '(TOP (S (NN "Eric") (VBZ "is") (JJ "testing"))))]
    (->> zipper
         nlp/iter-zip
         reverse
         next-two-words))

  )

(defn set-last [zipper f]
  (let [last-node (->> zipper
                       (iterate zip/next)
                       (take-while (complement zip/end?))
                       last
                       zip/prev)]
    (-> last-node
        (zip/replace (f last-node)))))

(comment
  (let [zipper (zip/seq-zip '(TOP (S (NN) (VBZ "is") (JJ))))]
    (-> zipper
        (set-last (fn [node] (list (zip/node (zip/next node)) "bad")))
        zip/root))

  )

(defn generate-with-markov
  [pos-path->word-freqs
   pos->word-freqs
   target-parse-tree
   markov]
  (loop [parse-zipper (zip/seq-zip target-parse-tree)]
    (cond
      (zip/end? parse-zipper) (zip/root parse-zipper)

      (zip/branch? parse-zipper)
      (recur (zip/next parse-zipper))

      (string? (zip/node parse-zipper))
      (recur (zip/next parse-zipper))

      (and (symbol? (zip/node parse-zipper))
           (or (pos->word-freqs (zip/node parse-zipper))
               (pos-path->word-freqs (seq (map first (zip/path parse-zipper))))))
      (let [target-path (seq (map first (zip/path parse-zipper)))
            target-pos (zip/node parse-zipper)
            pos-path-word (pos-path->word-freqs target-path)
            pos-word (pos->word-freqs target-pos)
            markov-options (markov (reverse (next-two-words (nlp/iter-zip
                                                             parse-zipper
                                                             zip/prev
                                                             nil?))))]
        (timbre/info "Markov options are"
                     (apply list (next-two-words (nlp/iter-zip
                                                  parse-zipper
                                                  zip/prev
                                                  nil?)))
                     (apply list (take 3 markov-options)))
        (timbre/info "Choosing POS for" target-path)
        (let [selection (weighted-rand/weighted-selection-from-map
                         (merge-with
                          *
                          (update-values markov-options * 10)
                          (update-values pos-path-word * 2)
                          pos-word))]
          (timbre/info "from" (apply
                               list
                               (take
                                5
                                (merge-with
                                 *
                                 (update-values markov-options * 10)
                                 (update-values pos-path-word * 2)
                                 pos-word))))
          (timbre/info "Chose " selection)
          (recur
           (-> parse-zipper
               zip/up
               (#(zip/replace % (list (zip/node (zip/down %)) selection)))
               zip/next
               zip/next))))

      :else
      (recur (zip/next parse-zipper)))))

(defn generate-with-markov-with-custom-progression
  "Sams as above, but with next/prev and stop fns"
  [next
   prev
   next-stop?
   prev-stop?
   pos-path->word-freqs
   pos->word-freqs
   parse-zipper
   markov]
  (loop [parse-zipper parse-zipper]
    (cond
      (nil? (next parse-zipper)) (zip/root parse-zipper)
     
      (next-stop? parse-zipper) (zip/root parse-zipper)

      (zip/branch? parse-zipper)
      (recur (next parse-zipper))

      (string? (zip/node parse-zipper))
      (recur (next parse-zipper))

      (and (symbol? (zip/node parse-zipper))
           (or (pos->word-freqs (zip/node parse-zipper))
               (pos-path->word-freqs (seq (map first (zip/path parse-zipper))))))
      (let [target-path (seq (map first (zip/path parse-zipper)))
            target-pos (zip/node parse-zipper)
            pos-path-word (pos-path->word-freqs target-path)
            pos-word (pos->word-freqs target-pos)
            pos-map (merge-with
                     (fn [a b] (* 1.5 (+ a b)))
                     pos-path-word
                     pos-word)
            markov-options (markov (reverse
                                    (next-two-words (nlp/iter-zip
                                                     parse-zipper
                                                     prev
                                                     prev-stop?))))
            selection-possibilities (merge-with
                                     (fn [a b]
                                       (let [max-pos (apply max (vals pos-map))]
                                         (+ a b max-pos)))
                                     pos-map
                                     markov-options)]
        (timbre/info "Markov options are"
                     (apply list (next-two-words (nlp/iter-zip
                                                  parse-zipper
                                                  prev
                                                  prev-stop?)))
                     (apply list (take 10 markov-options)))
        (timbre/info "Choosing POS for" target-path)
        (let [selection (weighted-rand/weighted-selection-from-map
                         selection-possibilities)]
          (timbre/info
           "Most likely selection possibilities"
           (apply list (take 5 (reverse (sort-by second selection-possibilities)))))
          (timbre/info "Chose " selection)
          (recur
           (-> parse-zipper
               zip/up
               (#(zip/replace % (list (zip/node (zip/down %)) selection)))
               zip/down
               next
               next))))

      :else
      (recur (next parse-zipper)))))

(defn generate-with-markov-with-custom-progression-n-2-pos-freqs
  "Sams as above, but with next/prev and stop fns"
  [next
   prev
   next-stop?
   prev-stop?
   pos-path->word-freqs
   parse-zipper
   markov]
  (loop [parse-zipper parse-zipper]
    (cond
      (nil? (next parse-zipper)) (zip/root parse-zipper)

      (next-stop? parse-zipper) (zip/root parse-zipper)

      (zip/branch? parse-zipper)
      (recur (next parse-zipper))

      (string? (zip/node parse-zipper))
      (recur (next parse-zipper))

      (and (symbol? (zip/node parse-zipper))
           (pos-path->word-freqs (take-last 2 (seq (map first (zip/path parse-zipper))))))
      (let [target-path (take-last 2 (seq (map first (zip/path parse-zipper))))
            target-pos (zip/node parse-zipper)
            pos-path-word (pos-path->word-freqs target-path)
            pos-map pos-path-word
            markov-options (markov (reverse
                                    (next-two-words (nlp/iter-zip
                                                     parse-zipper
                                                     prev
                                                     prev-stop?))))
            selection-possibilities (merge-with
                                     (fn [a b]
                                       (let [max-pos (apply max (vals pos-map))]
                                         (+ a b max-pos)))
                                     pos-map
                                     markov-options)]
        (timbre/info "Markov options are"
                     (apply list (next-two-words (nlp/iter-zip
                                                  parse-zipper
                                                  prev
                                                  prev-stop?)))
                     (apply list (take 10 markov-options)))
        (timbre/info "Choosing POS for" target-path)
        (let [selection (weighted-rand/weighted-selection-from-map
                         selection-possibilities)]
          (timbre/info
           "Most likely selection possibilities"
           (apply list (take 5 (reverse (sort-by second selection-possibilities)))))
          (timbre/info "Chose " selection)
          (recur
           (-> parse-zipper
               zip/up
               (#(zip/replace % (list (zip/node (zip/down %)) selection)))
               zip/down
               next
               next))))

      :else
      (recur (next parse-zipper)))))

(comment
  (let [structure '(TOP (S (NP (DT) (JJ) (NN))
                           (VP (RB) (VBZ))
                           (NP (DT) (JJ) (NN))))
        structure (-> structure
                      zip/seq-zip
                      nlp/iter-zip
                      last)
        pos-freqs (examples/pos-paths->pos-freqs
                   examples/t1)]
    (repeatedly
     10
     (fn []
       (->> (generate-with-markov-with-custom-progression-n-2-pos-freqs
             zip/prev
             zip/next
             nil?
             zip/end?
             examples/pos-freqs-data-2
             structure
             examples/darkov-2)))))

  (timbre/set-level! :info)
  (timbre/set-level! :error)

  (let [pos-path->word-freqs
        {'(S N) {"Eric" 1 "Edgar" 2}
         '(S V) {"tests" 2 "runs" 1}}
        pos->word-freqs
        {'N {"Edward" 1}
         'V {"breaks" 1}}
        target-parse-tree
        '(S (N) (V))]
    (-> (generate
         pos-path->word-freqs
         pos->word-freqs
         target-parse-tree)))
  (time (def example-pos-freqs examples/example-pos-freqs))
  (nippy/thaw)
  (nippy/freeze-to-file "resources/1000-pos-path-freqs.nip" example-pos-freqs)

  (time (def example-structures examples/example-structures))
  (weighted-rand/weighted-selection-from-map
   example-structures)



  (take 5 examples/t2)
  (let [structure (weighted-rand/weighted-selection-from-map
                   examples/popular-structure-freq-data)
        structure (-> structure
                      zip/seq-zip
                      nlp/iter-zip
                      last)
        pos-freqs examples/pos-freqs-data-2]
    (repeatedly
     10
     (fn []
       (->> (generate-with-markov-with-custom-progression-n-2-pos-freqs
             zip/prev
             zip/next
             nil?
             zip/end?
             pos-freqs
             structure
             examples/darkov-2)
            nlp/leaf-nodes
            (string/join " ")))))

  (repeatedly
   10
   (fn []
     (let [structure (weighted-rand/weighted-selection-from-map
                      (->> examples/t2
                           (sort-by second)
                           (reverse)
                           (take 20)))
           structure (-> structure
                         zip/seq-zip
                         nlp/iter-zip
                         last)
           pos-freqs (examples/pos-paths->pos-freqs
                      examples/t1)]
       (repeatedly
        10
        (fn []
          (->> (generate-with-markov-with-custom-progression
                zip/prev
                zip/next
                nil?
                zip/end?
                examples/t1
                pos-freqs
                structure
                examples/darkov-2)
               nlp/leaf-nodes
               (string/join " ")))))))

  )


;;; Most common grammars
(comment
  '([(TOP (NN)) 857]
    [(TOP (NP (NN) (NN))) 569]
    [(TOP (NP (JJ) (NN))) 563]
    [(TOP (NP (NP (NN)) (PP (IN) (NP (NN))))) 424]
    [(TOP (PP (IN) (NP (DT) (NN)))) 390]
    [(TOP (NP (NP (NN)) (PP (IN) (NP (DT) (NN))))) 314]
    [(TOP (NP (DT) (NN))) 300]
    [(TOP (NP (DT) (JJ) (NN))) 265]
    [(TOP (NP (NP (DT) (NN)) (PP (IN) (NP (NN))))) 250]
    [(TOP (VP (VB) (NP (DT) (NN)))) 221]
    [(TOP (NP (NP (NN)) (PP (IN) (NP (PRP$) (NN))))) 218]
    [(TOP (NP (JJ) (NNS))) 211]
    [(TOP (VB)) 204]))


(comment
  (def test-database (atom {::nlp/next-id 1}))

  (def texts
    (eduction
     (comp (df/xf-file-seq 0 250000)
           (map slurp))
     (file-seq (io/file "dark-corpus"))))

  (time
   (def test-trie
     (transduce
      (comp
       (map
        (fn [text]
          (try
            (nlp/text->grammar-trie-map-entry text)
            (catch Exception e
              (throw e)))))
       (map (partial map (nlp/make-database-stateful-xf test-database))))
      (completing
       (fn [trie entries]
         (reduce
          (fn [trie [k v]]
            (update trie k (fnil inc 0)))
          trie
          entries)))
      #_(trie/make-trie)
      test-trie
      (->> texts
           (drop 4000)
           (take 4000)))))

  )

(defn children
  [trie database k]
  (->> (trie/lookup trie k)
       (trie/children)
       (map #(vector (.key %) (get % [])))
       (remove (comp nil? second))
       (sort-by (comp - second))))

(defn choose
  [trie database k]
  (math/weighted-selection
   second
   (children trie database k)))

(defn markov-generate-grammar
  [trie database zipper]
  (cond
    (zip/end? zipper)
    (zip/root zipper)

    (seqable? (zip/node zipper))
    (recur trie database (zip/next zipper))

    (symbol? (zip/node zipper))
    (recur trie database (zip/next zipper))

    (symbol? (database (zip/node zipper)))
    (let [sym (database (zip/node zipper))
          sym-path  (->> (map first (zip/path zipper))
                         butlast
                         (filter symbol?)
                         (#(concat % (list sym))))
          path (map database sym-path)
          choice (first (choose trie database path))]
      (recur
       trie
       database
       (-> zipper
           (zip/replace
            [sym choice])
           (zip/root)
           (zip/vector-zip))))

    (string? (database (zip/node zipper)))
    (let [terminal (database (zip/node zipper))
          path (->> (map first (zip/path zipper))
                    butlast
                    (filter symbol?))]
      (recur
       trie
       database
       (-> zipper
           zip/remove
           zip/root
           zip/vector-zip)))

    :else
    (recur
     trie
     database
     (-> zipper
         (zip/replace
          (mapv
           database
           (database (zip/node zipper))))
         (zip/next)
         (zip/root)
         (zip/vector-zip)))))

(comment
  (->> (markov-generate-grammar test-trie @test-database (zip/vector-zip [1]))
       (zip/vector-zip)
       (nlp/iter-zip)
       (reverse)
       (map zip/node)
       ())

  (nippy/freeze-to-file "resources/grammar-trie-take-8000.bin" (seq test-trie))

  (nippy/freeze-to-file "resources/grammar-database-take-8000.bin" @test-database)

  )

(defn phrase->flex-rhyme-phones
  "Takes a space-seperated string of words
  and returns the concatenation of the words
  vowel phones.

  Returns them in reversed order so they
  are ready to be used in a lookup of a rhyme trie.
  "
  [phrase]
  (->> phrase
       (#(string/split % #" "))
       (map (comp owoga.syllabify/syllabify first owoga.phonetics/get-phones))
       (map (partial reduce into []))
       (map #(filter (partial re-find #"\d") %))
       (flatten)
       (map #(string/replace % #"\d" ""))
       (reverse)))

(defn markov-generate-grammar-with-rhyming-tail
  [grammar-trie grammar-database rhyme-trie rhyme-database rhyme-target zipper]
  (let [rhyme-phones (phrase->flex-rhyme-phones rhyme-target)
        rhyme-options (examples.tpt/rhyming-n-gram-choices
                       {:database rhyme-database
                        :flex-rhyme-trie rhyme-trie}
                       rhyme-target)
        rhyme-option-words (map (comp rhyme-database first first) rhyme-options)
        grammar (markov-generate-grammar
                 grammar-trie
                 grammar-database
                 zipper)
        tail [(grammar-database (zip/node (zipper-last (zip/vector-zip grammar))))]
        choices (map (comp grammar-database first)
                     (children grammar-trie grammar-database tail))
        intersection (set/intersection
                      (into #{} rhyme-option-words)
                      (into #{} choices))]
    (if (empty? intersection)
      (do
        (println (take 5 choices)
                 (take 5 rhyme-option-words))
        (markov-generate-grammar-with-rhyming-tail
         grammar-trie
         grammar-database
         rhyme-trie
         rhyme-database
         rhyme-target
         zipper))
      intersection)))

(defn markov-generate-sentence
  [trie database zipper]
  (cond
    (zip/end? zipper)
    (zip/root zipper)

    (seqable? (zip/node zipper))
    (recur trie database (zip/next zipper))

    (symbol? (zip/node zipper))
    (recur trie database (zip/next zipper))

    (symbol? (database (zip/node zipper)))
    (let [sym (database (zip/node zipper))
          sym-path  (->> (map first (zip/path zipper))
                         butlast
                         (filter symbol?)
                         (#(concat % (list sym))))
          path (map database sym-path)
          choice (first (choose trie database path))]
      (recur
       trie
       database
       (-> zipper
           (zip/replace
            [sym choice])
           (zip/root)
           (zip/vector-zip))))

    (string? (database (zip/node zipper)))
    (let [terminal (database (zip/node zipper))
          path (->> (map first (zip/path zipper))
                    butlast
                    (filter symbol?))]
      (recur
       trie
       database
       (-> zipper
           (zip/replace
            terminal)
           (zip/next)
           (zip/root)
           (zip/vector-zip))))

    :else
    (recur
     trie
     database
     (-> zipper
         (zip/replace
          (mapv
           database
           (database (zip/node zipper))))
         (zip/next)
         (zip/root)
         (zip/vector-zip)))))

(comment
  (markov-generate-sentence
   test-trie
   @test-database
   (zip/vector-zip [1]))

  (repeatedly
   20
   #(->> (generate test-trie @test-database (zip/vector-zip [1]))
         (zip/vector-zip)
         (iterate zip/next)
         (take-while (complement zip/end?))
         (map zip/node)
         (filter string?)))

  )

(defn visitor
  "Visit every node in a zipper traversing it
  with next-fn and applying apply-fn to every loc."
  [zipper next-fn apply-fn]
  (loop [loc zipper]
    (if (nil? (next-fn loc))
      (zip/vector-zip
       (zip/root (apply-fn loc)))
      (recur (next-fn (apply-fn loc))))))

(defn zipper-last
  [zipper]
  (->> zipper
       (iterate zip/next)
       (take-while (complement zip/end?))
       last))

(defn decode-fn
    "Decodes a variable-length encoded number from a byte-buffer.
  Zero gets decoded to nil."
    [byte-buffer]
    (let [value (encoding/decode byte-buffer)]
      (if (zero? value)
        nil
        value)))

(defn rest-leafs
  [zipper]
  (->> (nlp/iter-zip zipper)
       (filter (complement zip/branch?))
       (map zip/node)))

(defn choose-with-n-gram-markov
  "Hard-coded to work with 4-gram. That's the </s> at the end."
  [zipper
   grammar-trie
   grammar-database
   n-gram-trie
   n-gram-database]
  (let [prev-pos (previous-leaf-part-of-speech zipper)
        prev-pos' (map grammar-database prev-pos)
        n-gram (filter string? (concat (rest-leafs zipper) ["</s>" "</s>" "</s>"]))
        n-gram' (mapv tpt-db n-gram)
        part-of-speech-children (->> (children grammar-trie grammar-database (take-last 1 prev-pos'))
                                     (map #(vector (grammar-database (first %))
                                                   (second %))))
        grammar-children (->> (children grammar-trie grammar-database prev-pos')
                              (map #(vector (grammar-database (first %))
                                            (second %))))
        n-gram-children (loop [n-gram' (reverse (remove nil? (take 4 n-gram')))]
                          (if-let [node (trie/lookup n-gram-trie n-gram')]
                            (->> (trie/children node)
                                 (map #(vector (n-gram-database (.key %)) (get % []))))
                            (recur (rest n-gram'))))
        combined-choices (reduce
                          (fn [acc [k v]]
                            (update acc k (fnil + v)))
                          (into {} grammar-children)
                          n-gram-children)
        intersection (set/intersection
                      (into #{} (map first part-of-speech-children))
                      (into #{} (map first n-gram-children)))
        combined-choices (if (empty? intersection)
                           combined-choices
                           (select-keys combined-choices intersection))
        choice (math/weighted-selection
                second
                (seq combined-choices))]
    [n-gram
     n-gram'
     prev-pos
     prev-pos'
     part-of-speech-children
     grammar-children
     n-gram-children
     combined-choices
     choice]
    (first choice)))

(defn previous-leaf-loc
  [zipper]
  (->> zipper
       (iterate zip/prev)
       (take-while (complement nil?))
       (filter #(and (symbol? (zip/node %))
                     (zip/up %)
                     (= 1 (count (zip/node (zip/up %))))))
       (first)))

(defn previous-leaf-part-of-speech
  [zipper]
  (->> zipper
       previous-leaf-loc
       (zip/path)
       (map first)
       (filter symbol?)))

(defn nearest-ancestor-phrase
  [loc]
  (->> loc
       (iterate zip/prev)
       (take-while (complement nil?))
       (filter (comp tb2/phrases zip/node))
       (first)))

(comment
  (nearest-ancestor-phrase
   (->> (zip/vector-zip
         '[NP [NN]])
        zip/down
        zip/right
        zip/down)))

(comment
  ;; Working backwards from a completed grammar tree that has
  ;; been partially filled in with words, choose the next likely word
  ;; based off the grammar and an n-gram trie.
  (let [zipper (zip/vector-zip
                '[[TOP
                   [[VP
                     [[[VBN]]
                      [PP [[[TO]] [NP [[[NN ["storm"]]]]]]]
                      [PP [[[IN ["into"]]] [NP [[[PRP$ ["my"]]] [[NNS ["answers"]]]]]]]]]]]])
        loc (->> zipper
                 (iterate zip/next)
                 (filter #(= "storm" (zip/node %)))
                 (first))
        prev-pos (previous-leaf-part-of-speech loc)
        prev-pos' (map @test-database prev-pos)
        n-gram (filter string? (rest-leafs loc))
        n-gram' (mapv tpt-db n-gram)
        grammar-children (->> (children test-trie @test-database prev-pos')
                              (map first)
                              (map @test-database))
        n-gram-children (->> n-gram'
                             (take 2)
                             (reverse)
                             (trie/lookup tpt)
                             (trie/children)
                             (map #(vector (tpt-db (.key %)) (get % []))))]
    (choose-with-n-gram-markov
     loc test-trie @test-database tpt tpt-db))

  (let [zipper (zip/vector-zip
                '[[TOP
                   [[VP
                     [[[VBN]]
                      [PP [[[TO]] [NP [[[NN]]]]]]
                      [PP [[[IN]] [NP [[[PRP$]] [[NNS]]]]]]]]]]])
        loc (->> zipper
                 (iterate zip/next)
                 (take-while (complement zip/end?))
                 (last))
        prev-pos (previous-leaf-part-of-speech loc)
        prev-pos' (map @test-database prev-pos)
        n-gram (filter string? (rest-leafs loc))
        n-gram' (mapv tpt-db n-gram)
        grammar-children (->> (children test-trie @test-database prev-pos')
                              (map first)
                              (map @test-database))
        n-gram-children (->> n-gram'
                             (take 2)
                             (reverse)
                             (trie/lookup tpt)
                             (trie/children)
                             (map #(vector (tpt-db (.key %)) (get % []))))]
    (let [[n-gram
           n-gram'
           prev-pos
           prev-pos'
           part-of-speech-children
           grammar-children
           n-gram-children
           combined-choices
           choice]
          (choose-with-n-gram-markov
           loc test-trie @test-database tpt tpt-db)]
      [prev-pos
       (take 5 grammar-children)
       (take 5 n-gram-children)
       (take 5 combined-choices)
       choice]))

  (trie/lookup test-trie [1 59 3 5 5 17])
  (@test-database 1911)

  (def tpt (tpt/load-tightly-packed-trie-from-file
            (io/resource "dark-corpus-4-gram-backwards-tpt.bin")
            decode-fn))

  (def tpt-db (nippy/thaw-from-file (io/resource "dark-corpus-4-gram-backwards-db.bin")))
  (markov-generate-grammar test-trie @test-database (zip/vector-zip [1]))

  (-> (markov-generate-grammar test-trie @test-database (zip/vector-zip [1]))
      (zip/vector-zip)
      (zipper-last)
      (visitor
       zip/prev
       (fn [loc]
         (let [k (filter symbol? (map first (zip/path loc)))]
           (if (and (symbol? (zip/node loc))
                    (zip/up loc)
                    (= 1 (count (zip/node (zip/up loc))))
                    (not-empty k))
             (let [k' (map @test-database k)
                   choice (choose-with-n-gram-markov
                           loc
                           test-trie
                           @test-database
                           tpt
                           tpt-db)]
               (zip/replace
                loc
                [(zip/node loc)
                 [choice]]))
             loc)))))

  (-> (markov-generate-grammar test-trie @test-database (zip/vector-zip [1]))
      zip/vector-zip
      (zipper-last)
      (visitor
       zip/prev
       (fn [loc]
         (let [k (filter symbol? (map first (zip/path loc)))]
           (if (and (symbol? (zip/node loc))
                    (zip/up loc)
                    (= 1 (count (zip/node (zip/up loc))))
                    (not-empty k))
             (let [k' (map @test-database k)
                   choice (@test-database (first (choose
                                                  test-trie @test-database k')))]
               (zip/replace
                loc
                [(zip/node loc)
                 [choice]]))
             loc)))))

  (@test-database 497)
  )


(defn markov-choose-words-for-grammar
  [grammar-trie
   grammar-database
   n-gram-trie
   n-gram-database
   grammar]
  (-> grammar
      (visitor
       zip/prev
       (fn [loc]
         (println (zip/node loc))
         (if (and (tb2/words (zip/node loc))
                  (nil? (zip/right loc)))
           (do
             (println "inserting right" (zip/node loc))
             (zip/insert-right
              loc
              [(choose-with-n-gram-markov
                loc
                grammar-trie
                grammar-database
                n-gram-trie
                n-gram-database)]))
           loc)))))

(comment
  (let [grammar '[[TOP [[S [[NP [[NNS]]] [VP [[VBP]]] [NP [[NNS ["taylor"]]]]]]]]]
        sentence (markov-choose-words-for-grammar
                  test-trie
                  @test-database
                  tpt
                  tpt-db
                  (->> (zip/vector-zip grammar)
                       zipper-last
                       previous-leaf-loc))
        grammar2 `[[TOP [[S [[NP [[NNS]] [VP [[VBP]]] [NP [[NNS]]]]]]]]]
        rhyme (random-sample
               (markov-generate-grammar-with-rhyming-tail
                grammar2
                grammar-database
                rhyme-trie
                rhyme-database
                "taylor"
                ))]
    sentence)

  (markov-generate-grammar
   test-trie
   @test-database
   (zip/vector-zip [1]))

  (map @test-database '[TOP S NP NNS VP VBP]) ;; => (1 3 5 74 7 53)


  (markov-generate-grammar-with-rhyming-tail
   test-trie
   @test-database
   rhyme-trie
   rhyme-database
   "taylor"
   (zip/vector-zip [1]))

  )

(defn markov-complete-grammar-with-rhyming-tail
  [grammar-trie grammar-database rhyme-trie rhyme-database grammar rhyme-target]
  (let [rhyme-phones (phrase->flex-rhyme-phones rhyme-target)
        rhyme-options (examples.tpt/rhyming-n-gram-choices
                       {:database rhyme-database
                        :flex-rhyme-trie rhyme-trie}
                       rhyme-target)
        rhyme-option-words (map (comp rhyme-database first first) rhyme-options)
        tail [(grammar-database (zip/node (zipper-last (zip/vector-zip grammar))))]
        choices (map (comp grammar-database first)
                     (children grammar-trie grammar-database tail))
        intersection (set/intersection
                      (into #{} rhyme-option-words)
                      (into #{} choices))]
    (if (empty? intersection)
      (markov-complete-grammar-with-rhyming-tail
       grammar-trie
       grammar-database
       rhyme-trie
       rhyme-database
       rhyme-target)
      intersection)))

(defn markov-generate-sentence
  [trie database zipper]
  (cond
    (zip/end? zipper)
    (zip/root zipper)

    (seqable? (zip/node zipper))
    (recur trie database (zip/next zipper))

    (symbol? (zip/node zipper))
    (recur trie database (zip/next zipper))

    (symbol? (database (zip/node zipper)))
    (let [sym (database (zip/node zipper))
          sym-path  (->> (map first (zip/path zipper))
                         butlast
                         (filter symbol?)
                         (#(concat % (list sym))))
          path (map database sym-path)
          choice (first (choose trie database path))]
      (recur
       trie
       database
       (-> zipper
           (zip/replace
            [sym choice])
           (zip/root)
           (zip/vector-zip))))

    (string? (database (zip/node zipper)))
    (let [terminal (database (zip/node zipper))
          path (->> (map first (zip/path zipper))
                    butlast
                    (filter symbol?))]
      (recur
       trie
       database
       (-> zipper
           (zip/replace
            terminal)
           (zip/next)
           (zip/root)
           (zip/vector-zip))))

    :else
    (recur
     trie
     database
     (-> zipper
         (zip/replace
          (mapv
           database
           (database (zip/node zipper))))
         (zip/next)
         (zip/root)
         (zip/vector-zip)))))


(comment
  (generate-grammar-from [[NN ["taylor"]]])
  (map (comp (partial map @test-database) first) (take 5 test-trie))

  (@test-database 1)
  )
