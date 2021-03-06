(ns examples.tpt
  (:require [clojure.string :as string]
            [clojure.java.io :as io]
            [com.owoga.phonetics :as owoga.phonetics]
            [com.owoga.phonetics.syllabify :as owoga.syllabify]
            [com.owoga.prhyme.core :as prhyme]
            [com.owoga.prhyme.nlp.core :as nlp]
            [taoensso.tufte :as tufte :refer (defnp p profiled profile)]
            [com.owoga.trie :as trie]
            [com.owoga.tightly-packed-trie :as tpt]
            [com.owoga.trie.math :as math]
            [com.owoga.tightly-packed-trie.encoding :as encoding]
            [com.owoga.prhyme.util :as util]
            [com.owoga.prhyme.data.dictionary :as dict]
            [clojure.zip :as zip]
            [cljol.dig9 :as d]
            [com.owoga.prhyme.data.phonetics :as phonetics]
            [com.owoga.prhyme.syllabify :as syllabify]
            [taoensso.nippy :as nippy]))

(tufte/add-basic-println-handler! {})

(defn xf-file-seq [start end]
  (comp (remove #(.isDirectory %))
        (drop start)
        (take end)))

(def re-word
  "Regex for tokenizing a string into words
  (including contractions and hyphenations),
  commas, periods, and newlines."
  #"(?s).*?([a-zA-Z\d]+(?:['\-]?[a-zA-Z]+)?|,|\.|\n)")

(defn pad-tokens
  "Pads the beginning with n - 1 <s> tokens and
  the end with 1 </s> token."
  [tokens n]
  (vec (concat (vec (repeat (max 1 (dec n)) "<s>")) tokens ["</s>"])))

(defn tokenize-line
  [line]
  (->> line
       (string/trim)
       (re-seq re-word)
       (mapv second)
       (mapv string/lower-case)))

(defn text->ngrams
  "Takes text from a file, including newlines.
  Pads lines with <s> and </s> for start/end of line.
  Pads beginning with n - 1 <s>s"
  [text n]
  (->> text
       util/clean-text
       (#(string/split % #"\n+"))
       (remove empty?)
       (mapv tokenize-line)
       (mapv #(pad-tokens % n))
       (mapv #(partition n 1 %))
       (mapv #(mapv vec %))
       (reduce #(into %1 %2) [])))

(defn text->backwards-ngrams
  "Takes text from a file, including newlines.
  Pads lines with <s> and </s> for start/end of line.
  Pads beginning with n - 1 <s>s"
  [text n]
  (->> text
       util/clean-text
       (#(string/split % #"\n+"))
       (remove empty?)
       (mapv tokenize-line)
       (mapv #(pad-tokens % n))
       reverse
       (mapv reverse)
       (mapv #(partition n 1 %))
       (mapv #(mapv vec %))
       (reduce #(into %1 %2) [])))

(defn n-to-m-grams
  "Exclusive of m, similar to range."
  [n m text]
  (loop [i n
         r []]
    (cond
      (= i m)
      r
      :else
      (recur (inc i)
             (into r (text->ngrams text i))))))

(defn n-to-m-backwards-grams
  "Exclusive of m, similar to range."
  [n m text]
  (loop [i n
         r []]
    (cond
      (= i m)
      r
      :else
      (recur (inc i)
             (into r (text->backwards-ngrams text i))))))

(declare ->TrieKey)

(deftype TrieKey [key]
  clojure.lang.IPersistentStack
  (peek [self]
    (let [x (last (seq self))]
      (if (.equals "" x)
        nil
        (Integer/parseInt x))))
  (pop [self]
    (TrieKey. (string/replace key #"(.*):.*$" "$1")))

  clojure.lang.ISeq
  (first [self]
    (let [x (first (seq self))]
      (if (.equals x "")
        nil
        (Integer/parseInt x))))
  (next [self]
    (TrieKey. (string/replace key #".*?:(.*)" "$1")))
  (more [self]
    (let [xs (string/split key #":")]
      (if (.equals xs "") '() (into (->TrieKey "") (rest xs)))))
  (cons [self o]
    (TrieKey.
     (cond
       (.equals key "") ":"
       (.equals key ":") (str key o)
       :else (str key ":" o))))

  clojure.lang.IPersistentCollection
  (count [self]
    (count (seq self)))
  (empty [self]
    (TrieKey. ""))
  (equiv [self o]
    (.equals self o))

  clojure.lang.Seqable
  (seq [self]
    (if (.equals "" key)
      nil
      (seq (string/split key #":")))))

(defmethod print-method TrieKey [trie-key ^java.io.Writer w]
  (print-method (.key trie-key) w))

(defmethod print-dup TrieKey [trie-key ^java.io.Writer w]
  (print-ctor trie-key (fn [o w] (print-dup (.key trie-key) w)) w))

(defn trie-key
  ([]
   (->TrieKey ""))
  ([coll]
   (->TrieKey (string/join ":" coll))))


(def trie-database (atom nil))

(defn stateful-transducer [xf]
  (let [trie (volatile! (trie/make-trie))
        database (atom {})
        next-id (volatile! 1)]
    (fn
      ([] (xf))
      ([result]
       (reset! trie-database @database)
       (xf result))
      ([result input]
       (let [ngrams-ids
             (mapv
              (fn [ngrams]
                (mapv
                 (fn [ngram]
                   (let [gram-ids (mapv
                                   (fn [gram]
                                     (let [gram-id (get @database gram @next-id)]
                                       (when (.equals gram-id @next-id)
                                         (swap! database
                                                #(-> %
                                                     (assoc gram gram-id)
                                                     (assoc gram-id gram)))
                                         (vswap! next-id inc))
                                       gram-id))
                                   ngram)
                         ngram-id (get database gram-ids @next-id)]
                     gram-ids))
                 ngrams))
              input)]
         (vswap!
          trie
          (fn [trie ngrams-ids]
            (reduce
             (fn [trie [ngram-ids _]]
               (update trie ngram-ids (fnil #(update % 1 inc) [(peek ngram-ids) 0])))
             trie
             ngrams-ids))
          ngrams-ids))))))

(defn prep-ngram-for-trie
  "The tpt/trie expects values conjed into an ngram
  to be of format '(k1 k2 k3 value)."
  [ngram]
  (clojure.lang.MapEntry. (vec ngram) ngram))

(defn seq-of-nodes->sorted-by-count
  "Sorted first by the rank of the ngram, lowest ranks first.
  Sorted second by the frequency of the ngram, highest frequencies first.
  This is the order that you'd populate a mapping of keys to IDs."
  [trie]
  (->> trie
       trie/children
       (map #(get % []))
       (sort-by :count)
       reverse))

(defn rhyme-trie-transducer [xf]
  (let [trie (volatile! (trie/make-trie))
        database (atom {})
        next-id (volatile! 1)]
    (fn
      ([] (xf))
      ([result]
       (reset! trie-database @database)
       (xf result))
      ([result input]
       (let [ngrams-ids
             (mapv
              (fn [ngrams]
                (mapv
                 (fn [ngram]
                   (let [gram-ids (mapv
                                   (fn [gram]
                                     (let [gram-id (get @database gram @next-id)]
                                       (when (.equals gram-id @next-id)
                                         (swap! database
                                                #(-> %
                                                     (assoc gram gram-id)
                                                     (assoc gram-id gram)))
                                         (vswap! next-id inc))
                                       gram-id))
                                   ngram)
                         ngram-id (get database gram-ids @next-id)]
                     gram-ids))
                 ngrams))
              input)]
         (vswap!
          trie
          (fn [trie ngrams-ids]
            (reduce
             (fn [trie [ngram-ids _]]
               (update trie ngram-ids (fnil #(update % 1 inc) [(peek ngram-ids) 0])))
             trie
             ngrams-ids))
          ngrams-ids))))))

(comment
  (transduce (comp (xf-file-seq 0 10)
                   (map slurp)
                   (map (partial n-to-m-grams 1 5))
                   #_#_(map (fn [ngrams] (map #(prep-ngram-for-trie %) ngrams)))
                   stateful-transducer)
             conj
             (file-seq (io/file "dark-corpus")))

  (time
   (def trie
     (transduce (comp (xf-file-seq 0 250000)
                      (map slurp)
                      (map (partial n-to-m-grams 1 4))
                      (map (fn [ngrams] (map #(prep-ngram-for-trie %) ngrams)))
                      stateful-transducer)
                conj
                (file-seq (io/file "dark-corpus")))))

  (time
   (def backwards-trie
     (transduce (comp (xf-file-seq 0 1000)
                      (map slurp)
                      (map (partial n-to-m-backwards-grams 1 4))
                      (map (fn [ngrams] (map #(prep-ngram-for-trie %) ngrams)))
                      stateful-transducer)
                conj
                (file-seq (io/file "dark-corpus")))))

  )

(defn encode-fn [v]
  (let [[value count] (if (seqable? v) v [nil nil])]
    (if (nil? value)
      (encoding/encode 0)
      (byte-array
       (concat (encoding/encode value)
               (encoding/encode count))))))

(defn decode-fn [db]
  (fn [byte-buffer]
    (let [value (encoding/decode byte-buffer)]
      (if (zero? value)
        nil
        [value (encoding/decode byte-buffer)]))))

(comment
  (time
   (def tightly-packed-trie
     (tpt/tightly-packed-trie
      trie
      encode-fn
      (decode-fn @trie-database))))

  (time
   (def tightly-packed-backwards-trie
     (tpt/tightly-packed-trie
      backwards-trie
      encode-fn
      (decode-fn @trie-database))))

  )

(defn key-get-in-tpt [tpt db ks]
  (let [id (map #(get-in db [(list %) :id]) ks)
        v (get tpt id)]
    {id v}))

(defn id-get-in-tpt [tpt db ids]
  (let [ks (apply concat (map #(get db %) ids))
        v (get tpt ids)
        id (get-in db [ks :id])]
    {ks (assoc v :value (get db id))}))


(defn clone-consonants [phones]
  (map
   #(if (phonetics/vowel (string/replace % #"\d" ""))
      %
      "?")
   phones))

(defn word->phones [word]
  (or (dict/word->cmu-phones word)
      (util/get-phones-with-stress word)))

(defn perfect-rhymes [rhyme-trie phones]
  (let [rhyme-suffix (first
                      (util/take-through
                       #(= (last %) \1)
                       (reverse phones)))]
    (trie/lookup rhyme-trie rhyme-suffix)))

(defn vowel-rhymes [rhyme-trie phones]
  (let [rhyme-suffix (->> (reverse phones)
                          (clone-consonants)
                          (util/take-through #(= (last %) \1))
                          (first))]
    (trie/lookup rhyme-trie rhyme-suffix)))

(defn n+1grams [trie k]
  (->> (trie/lookup trie k)
       (trie/children)
       (map #(get % []))))

(defn word->n+1grams [trie database word]
  (->> word
       database
       (#(trie/lookup trie [%]))
       trie/children
       (map #(get % []))
       (map (fn [[id fr]] [(database id) fr]))
       (sort-by (comp - #(nth % 1)))
       (remove #({"<s>" "</s>"} (nth % 0)))))

(comment
  (let [trie (@context :trie)
        db (@context :database)]
    (word->n+1grams trie db "technology"))

  )

(defn phrase->phones [phrase]
  (let [words (string/split phrase #"[ -]")]
    (->> words
         (map word->phones)
         (map syllabify/syllabify))))

(defn syllabify-with-stress [word]
  (let [phones (word->phones word)
        phones-without-stress (map #(string/replace % #"\d" "") phones)
        syllables (first (owoga.syllabify/syllabify phones-without-stress))]
    (loop [phones phones
           syllables syllables
           result [[]]]
      (cond
        (empty? syllables)
        (map seq (pop result))

        (empty? (first syllables))
        (recur
         phones
         (rest syllables)
         (conj result []))

        :else
        (recur
         (rest phones)
         (cons (rest (first syllables))
               (rest syllables))
         (conj (pop result)
               (conj (peek result) (first phones))))))))

(defn syllabify-phrase-with-stress [phrase]
  (reduce
   into
   []
   (map
    (comp owoga.syllabify/syllabify
          first
          owoga.phonetics/get-phones)
    (string/split phrase #"[ -]"))))

(comment
  (syllabify-phrase-with-stress "bother me")

  (word->phones "bother me")

  (map (comp owoga.syllabify/syllabify first owoga.phonetics/get-phones) ["bother" "me"])

  [(syllabify-phrase-with-stress "on poverty")
   (syllabify-phrase-with-stress "can bother me")]

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

(comment
  (phrase->flex-rhyme-phones "bother hello")
  ;; => ("OW" "AH" "ER" "AA")
  )

(defonce context (atom {}))

(defn initialize []
  (swap!
   context
   assoc
   :database
   (with-open [rdr (clojure.java.io/reader "resources/backwards-database.bin")]
     (into {} (map read-string (line-seq rdr)))))

  (swap!
   context
   assoc
   :trie
   (tpt/load-tightly-packed-trie-from-file
    "resources/dark-corpus-backwards-tpt.bin"
    (decode-fn (@context :database))))

  (swap!
   context
   assoc
   :perfect-rhyme-trie
   (transduce
    (comp
     (map first)
     (filter string?)
     (map #(vector % (reverse (word->phones %))))
     (map reverse))
    (completing
     (fn [trie [k v]]
       (update trie k (fnil #(update % 1 inc) [v 0]))))
    (trie/make-trie)
    (@context :database)))

  (swap!
   context
   assoc
   :rhyme-trie
   (transduce
    (comp
     (map first)
     (filter string?)
     (map #(vector % (reverse (word->phones %))))
     (map reverse))
    (completing
     (fn [trie [k v]]
       (update trie k (fnil #(update % 1 inc) [v 0]))))
    (trie/make-trie)
    (@context :database)))

  #_(swap!
   context
   assoc
   :flex-rhyme-trie
   (transduce
    (comp
     (map first)
     (filter string?)
     (map #(vector (reverse (phrase->flex-rhyme-phones %)) %)))
    (completing
     (fn [trie [k v]]
       (update trie k (fnil conj [v]) v)))
    (trie/make-trie)
    (@context :database)))
  nil)

;; From a tightly-packed-trie and a database, build a trie
;; of phones of n-grams
(comment
  (do
    (time
     (swap!
      context
      assoc
      :flex-rhyme-trie
      (transduce
       (comp
        (map (fn [[k v]]
               [(string/join " " (map (@context :database) k))
                [k v]]))
        (map (fn [[phrase [k v]]]
               [(phrase->flex-rhyme-phones phrase)
                [k v]])))
       (completing
        (fn [trie [k v]]
          (update trie k (fnil conj [v]) v)))
       (trie/make-trie)
       (->> (trie/children-at-depth (@context :trie) 0 1)))))
    nil)

  (take 5 (@context :flex-rhyme-trie))
  )

(comment
  (->> (get (@context :flex-rhyme-trie) ["EH" "OW" "IY" "EH"])
       (take 20)
       (map first)
       (map (partial map (@context :database))))
  (trie/children (trie/lookup (@context :trie) [13393]))
  ((@context :database) "desk")   ;; => 13393
  ((@context :database) "wobbly") ;; => 152750
  (get (@context :trie) [13393 152750]))

(defn rhyme-choices
  [{:keys [flex-rhyme-trie database] :as context} phrase]
  (if (string? phrase)
    (let [phones (phrase->flex-rhyme-phones phrase)]
      (get flex-rhyme-trie phones))
    (get flex-rhyme-trie phrase)))

(defn exclude-non-rhymes-from-choices
  "Removes any choice that includes the last
  word of the rhyming phrase as the last word of the choice.

  Also removes beginning and end of sentence markers (1 and 38 in the database)."
  [{:keys [database]} phrase choices]
  (if (string? phrase)
    (let [word-id (database (last (string/split phrase #" ")))]
      (remove
       (fn [child]
         (or (= ((comp first second) child) word-id)
             (#{1 38} ((comp first first) child))))
       choices))
    (remove
     (fn [child] (#{1 38} ((comp first first) child)))
     choices)))

(defn exclude-non-english-phrases-from-choices
  [{:keys [database]} choices]
  (filter
   (fn [choice]
     (->> (first choice)
          (map database)
          (every? dict/cmu-with-stress-map)))
   choices))

(defn weighted-selection-from-choices
  [choices]
  (math/weighted-selection
   (comp second second)
   choices))

(defn choice->n-gram
  [{:keys [database]} choice]
  (map database (first choice)))

(defn generate-rhyming-n-gram
  [phrase]
  (->> (rhyme-choices @context phrase)
       (exclude-non-rhymes-from-choices @context phrase)
       (weighted-selection-from-choices)
       (choice->n-gram @context)))

(defn get-flex-rhyme
  "Gets from a rhyme-trie a rhyming n-gram based on the
  weighted selection from their frequencies."
  [{:keys [flex-rhyme-trie database] :as context} phrase]
  (if (string? phrase)
    (let [phones (phrase->flex-rhyme-phones phrase)
          ;; Exclude the last word. Don't rhyme kodak with kodak.
          word-id (database (first (string/split phrase #" ")))
          choices (remove
                   (fn [child]
                     (= (first child) word-id))
                   (get flex-rhyme-trie phones))
          choice (math/weighted-selection
                  (comp second second)
                  choices)]
      (map database (first choice)))
    (let [phones phrase
          choices (get flex-rhyme-trie phones)
          choice (math/weighted-selection
                  (comp second second)
                  choices)]
      (map database (first choice)))))

(comment
  (get-flex-rhyme @context "bother me")
  (phrase->flex-rhyme-phones "bother me")
  (get-flex-rhyme @context ["IY" "ER" "AA"])
  )

(defn get-next-markov
  [{:keys [trie database] :as context} seed]
  (let [seed (take-last 3 seed)
        node (trie/lookup trie seed)
        children (and node
                      (->> node
                           trie/children
                           (map #(vector (.key %) (get % [])))
                           (remove (comp nil? second))
                           (remove
                            (fn [[k v]]
                              (#{1 38} k)))))]
    (cond
      (nil? node) (recur context (rest seed))
      (seq children)
      (if (< (rand) (/ (apply max (map (comp second second) children))
                       (apply + (map (comp second second) children))))
        (recur context (rest seed))
        (first (math/weighted-selection (comp second second) children)))
      (> (count seed) 0)
      (recur context (rest seed))
      :else (throw (Exception. "Error")))))

(defn get-next-markov-from-phrase-backwards
  [{:keys [database trie] :as context} phrase n]
  (let [word-ids (->> phrase
                      (#(string/split % #" "))
                      (take n)
                      (reverse)
                      (map database))]
    (database (get-next-markov context word-ids))))

(comment
  (get-next-markov @context [222])
  (get-next-markov-from-phrase-backwards @context "will strike you down" 3)

  (get (@context :database) 7982)
  )
(defn ids->words
  [{:keys [database] :as context} ids]
  (map database ids))

(defn words->syllables
  [words]
  (->> words
       (string/join " ")
       (reverse (phrase->flex-rhyme-phones))))

(defn generate-sentence-with-n-words
  [{:keys [database] :as context} seed n]
  (loop [seed seed]
    (if (>= (dec n) (count seed))
      (recur (conj seed (get-next-markov context seed)))
      (map database seed))))

(defn take-words-amounting-to-at-least-n-syllables
  [phrase n]
  (letfn [(phones [word]
            [word (first (owoga.phonetics/get-phones word))])
          (syllables [[word phones]]
            [word (owoga.syllabify/syllabify phones)])]
    (->> phrase
         (#(string/split % #" "))
         (map phones)
         (map syllables)
         (reduce
          (fn [result [word syllables]]
            (if (<= n (count (mapcat second result)))
              (reduced result)
              (conj result [word syllables])))
          [])
         (map first)
         (string/join " "))))

(defn take-n-syllables
  [phrase n]
  (if (string? phrase)
    (->> phrase
         (phrase->flex-rhyme-phones)
         (take n)
         (reverse))
    (take-last n phrase)))

(take-n-syllables "bother me" 2)

(defn valid-english-sentence?
  [phrase]
  (let [words (string/split #" " phrase)]
    (and (nlp/valid-sentence? phrase)
         (every? dict/cmu-with-stress-map words))))


(defn sha256 [text]
  (let [digest (java.security.MessageDigest/getInstance "SHA-256")]
    (->> (.digest digest (.getBytes text "UTF-8"))
         (#(BigInteger. 1 %))
         (#(.toString % 16)))))

(defn syllable-count-phrase
  [phrase]
  (->> phrase
       (#(string/split % #" "))
       (map owoga.phonetics/get-phones)
       (map first)
       (mapcat owoga.syllabify/syllabify)
       count))

(defn rhyming-n-gram-choices
  [context target-rhyme]
  (loop [target-rhyme target-rhyme]
    (let [choices (->> target-rhyme
                       (rhyme-choices context)
                       (exclude-non-rhymes-from-choices context target-rhyme)
                       (exclude-non-english-phrases-from-choices context))]
      (if (empty? choices)
        (recur (if (string? target-rhyme)
                 (butlast (phrase->flex-rhyme-phones target-rhyme))
                 (butlast target-rhyme)))
        choices))))

(comment
  (->> (rhyming-n-gram-choices @context "fall")
       (map (comp (@context :database) first first)))

  )


(defn generate-n-syllable-sentence-rhyming-with
  [context target-phrase n-gram-rank target-rhyme-syllable-count target-sentence-syllable-count]
  (if (string? target-phrase)
    (let [target-phrase-words (string/split target-phrase #" ")
          reversed-target-phrase (string/join " " (reverse target-phrase-words))
          target-rhyme
          (->> (take-words-amounting-to-at-least-n-syllables
                reversed-target-phrase
                target-rhyme-syllable-count)
               (#(string/split % #" "))
               reverse
               (string/join " "))
          rhyming-n-gram (->> (rhyming-n-gram-choices context target-rhyme)
                              (weighted-selection-from-choices)
                              (choice->n-gram context)
                              (string/join " "))]
      (loop [phrase rhyming-n-gram]
        (if (<= target-sentence-syllable-count (syllable-count-phrase phrase))
          phrase
          (recur
           (str (get-next-markov-from-phrase-backwards context phrase n-gram-rank)
                " "
                phrase)))))
    (let [target-rhyme
          (->> (take-n-syllables target-phrase target-rhyme-syllable-count))
          rhyming-n-gram (->> (rhyming-n-gram-choices context target-rhyme)
                              (weighted-selection-from-choices)
                              (choice->n-gram context)
                              (string/join " "))]
      (loop [phrase rhyming-n-gram]
        (if (<= target-sentence-syllable-count (syllable-count-phrase phrase))
          phrase
          (recur
           (str (get-next-markov-from-phrase-backwards context phrase n-gram-rank)
                " "
                phrase)))))))

(defn generate-haiku
  [seed]
  (let [haiku (cons
               seed
               (map
                #(generate-n-syllable-sentence-rhyming-with
                  @context
                  (take 3 (phrase->flex-rhyme-phones seed))
                  3 3 %)
                [5 3]))]
    (lazy-seq
     (cons
      haiku
      (generate-haiku
       (last haiku))))))

(comment
  (defn valid-haiku [haiku]
    (and
     (or (every? nlp/valid-sentence? haiku)
         (->> haiku
              (mapcat #(string/split % #" "))
              (every? dict/cmu-with-stress-map)))
     (->> haiku
          (map #(string/split % #" "))
          (map last)
          (apply distinct?))))

  (->> (generate-haiku "football")
       (filter valid-haiku)
       (map (partial string/join "\n"))
       (map #(vector % (sha256 %)))
       (map (fn [[haiku sha]]
              (println haiku)
              (println sha)
              (println)))
       (take 10))

  )

(comment
  (println
   (string/join
    "\n"
    (map
     #(generate-n-syllable-sentence-rhyming-with
       @context
       (take 3 (phrase->flex-rhyme-phones "player unknown battlegrounds"))
       3 4 %)
     [9 6 6 9 6 6 9 6 6]))))

"
another day a battleground
contorts the fragile sound
that no one gives a damn about
what is the chaos all about
have we really been trampled down
cause they'll pay be blasted now
weeping to absent cow
die die lightning all around
calling for santa's now
hours of lifes battleground
just how much more could a man about
trample on and as without
i'm just like so so fragile how
killing and i'll be close damage wow
witness sky is blackened now
"


(defn amulate?
  [text]
  (let [digest (sha256 text)]
    (re-matches #"8{4}" digest)))

(defn continuously-amulate
  [seed]
  (let [next-sentence (amul8 seed)
        next-seed (->> next-sentence
                       (#(string/split % #" "))
                       (reverse)
                       (map
                        (fn [word]
                          [word (phrase->flex-rhyme-phones word)]))
                       ((fn [word-phones]
                          (loop [word-phones word-phones
                                 seed []]
                            (if (< 2 (count (mapcat second seed)))
                              (string/join
                               " "
                               (reverse (map first seed)))
                              (recur (rest word-phones)
                                     (conj seed (first word-phones))))))))]
    (lazy-seq
     (cons next-sentence (continuously-amulate next-seed)))))

(comment
  (take 5 (continuously-amulate "technology"))

  (->> (amul8 "technology" 1)
       (map second)
       (partition 2 1)
       (map
        (fn [pair]
          (string/join "\n" pair)))
       (map #(vector % (sha256 %)))
       (map
        (fn [[text sha]]
          [text sha (re-matches #"8{4}" sha)])))

  (dict/cmu-with-stress-map )
  (repeatedly
   3
   #(amulate (reverse ["pleasure" "of" "the" "arcane" "technology"])))

  (phrase->flex-rhyme-phones "bother hello")
  (phrase->flex-rhyme-phones "snow-covered on")
  (get-flex-rhyme @context (reverse ["AA" "ER" "AH" "OW"]))
  ((@context :database) "<s>")
  (get-next-markov @context [1 503])

  (take 20
        (repeatedly #(reverse (get-flex-rhyme @context
                                              (reverse (phrase->flex-rhyme-phones "technology"))
                                              "technology"))))

  (amulate)

  (get (@context :database) "</s>")
  (get (@context :database) "technology")
  (phrase->flex-rhyme-phones "able") ;; => ("EY" "AH")
  (phrase->flex-rhyme-phones "away") ;; => ("AH" "EY")
  (take 20 (@context :flex-rhyme-trie))
  (get-flex-rhyme @context '("AA" "IY" "AE"))

  (map #(get (@context :database) %) [1 503])
  (time (count (tpt/children-at-depth (@context :trie) 0 2)))

  (->> (trie/children-at-depth (@context :flex-rhyme-trie') 0 5)
       (take 500))

  (trie/children (trie/lookup (@context :flex-rhyme-trie')
                              (reverse (rest (phrase->flex-rhyme-phones "i love you")))))

  (trie/lookup (@context :flex-rhyme-trie') '("IY" "AH" "AA"))

  (map (@context :database) '())
  (take 5 (@context :flex-rhyme-trie'))

  (map #(get (@context :database) %) [21 8953])
  (map #(get (@context :database) %) [410 48670])
  (get (@context :trie) [1 2 2])

  (trie/children (trie/lookup (@context :trie) [1 2]))

  (first (@context :trie))
  ;; 448351
  ;; 4388527
  (time (initialize))

  )

(defn flex-rhymes->phrases [flex-rhymes database]
  (->> flex-rhymes
       (map second)
       (map
        (fn [rhymes]
          (reduce
           (fn [acc [k [v fr]]]
             (update acc k (fnil #(+ % fr) 0)))
           {}
           rhymes)))
       (map (partial sort-by (comp - second)))
       (map
        (fn [rhymes]
          (map
           (fn [[k fr]]
             [(map database k) fr])
           rhymes)))))

(comment
  (->> (trie/lookup
        (@context :flex-rhyme-trie3')
        (reverse (phrase->flex-rhyme-phones "taylor my dear")))
       (#(flex-rhymes->phrases % (@context :database)))
       (apply concat)
       (sort-by (comp - second))
       (remove
        (fn [[k fr]]
          (or (= 1 (count k))
              (= "</s>" (first k))
              (= "<s>" (second k))))))

  (filter
   dict/english?
   (flatten
    (map #(get % [])
         (trie/children
          (trie/lookup
           (@context :flex-rhyme-trie)
           '("IY" "AH" "AA"))))))

  (->> (take 5 (drop 500 (@context :flex-rhyme-trie')))
       (#(flex-rhymes->phrases % (@context :database))))

  (let [key (reverse (phrase->flex-rhyme-phones "technology"))]
    [key
     (reverse (phrase->flex-rhyme-phones "sociology"))
     (get (@context :flex-rhyme-trie) key)
     (get (@context :flex-rhyme-trie) (rest key))])

  )

(defn find-rhymes
  "Takes a rhyme-trie (perfect or vowel only, for example)
  and a word. Returns list of rhyming words."
  [trie word]
  (->> (perfect-rhymes trie (or (dict/cmu-with-stress-map word)
                                (util/get-phones-with-stress word)))
       (map (comp first second))
       (remove nil?)
       (map (@context :database))
       (map #(get (@context :trie) [%]))
       (sort-by #(nth % 1))
       (reverse)
       (map
        (fn [[word-id freq]]
          [((@context :database) word-id)
           freq]))
       (remove #(= word (first %)))))

(defn choose-next-word
  "Given an n-gram of [[word1 freq1] [word2 freq2]] chooses
  the next word based on markove data in trie."
  [{:keys [database trie] :as context} n-gram]
  (let [n-gram-ids (->> n-gram (map first) (map database))
        node (trie/lookup trie n-gram-ids)]
    (cond
      (= 0 (count n-gram-ids))
      (let [children (->> (trie/children trie)
                          (map #(get % [])))
            choice (math/weighted-selection second children)]
        [(database (first choice)) (second choice)])
      node
      (let [children (->> (trie/children node)
                          (map #(get % []))
                          (remove (fn [[id f]] (= id (first n-gram-ids)))))]
        (if (seq children)
          (let [children-freqs (into (sorted-map) (frequencies (map second children)))
                n-minus-1-gram-odds (/ (second (first children-freqs))
                                       (+ (second (get node []))
                                          (second (first children-freqs))))
                take-n-minus-1-gram? (and (< 1 (count n-gram-ids))
                                          (< (rand) n-minus-1-gram-odds))]
            (if take-n-minus-1-gram?
              (choose-next-word context (butlast n-gram))
              (let [choice (math/weighted-selection second children)]
                [(database (first choice)) (second choice)])))
          (choose-next-word context (butlast n-gram))))
      :else
      (choose-next-word context (butlast n-gram)))))

(defn remove-sentence-markers [phrase]
  (remove (fn [[word _]] (#{"<s>" "</s>"} word)) phrase))

(defn valid-sentence? [phrase]
  (->> phrase
       (map first)
       (string/join " ")
       (#(string/replace % #"(<s>|</s>)" ""))
       (nlp/valid-sentence?)))

(defn valid-sentences? [phrase]
  (let [sentences (->> (util/take-through
                        #(= (first %) "</s>")
                        phrase)
                       (map remove-sentence-markers))]
    sentences))

(defn generate-phrase [{:keys [database trie] :as context} phrase]
  (loop [phrase' (loop [phrase phrase]
                   (if (< 5 (count phrase))
                     phrase
                     (recur (cons (choose-next-word context (take 3 phrase))
                                  phrase))))]
    (if (valid-sentence? phrase')
      phrase'
      (recur (loop [phrase phrase]
               (if (< 5 (count phrase))
                 phrase
                 (recur (cons (choose-next-word context (take 3 phrase))
                              phrase))))))))

(defn generate-sentence-backwards
  "Given a phrase of [w1 w2 w3] generates a sentence
  using a backwards markov."
  ([{:keys [database trie] :as context} phrase]
   (let [phrase (map (fn [w]
                       (let [id (database w)]
                         [w (second (get trie [id]))]))
                     phrase)]
     (loop [phrase' (loop [phrase phrase]
                      (if (= "<s>" (first (first phrase)))
                        phrase
                        (recur (cons (choose-next-word context (take 3 phrase))
                                     phrase))))]
       (if (valid-sentence? phrase')
         phrase'
         (recur (loop [phrase phrase]
                  (if (= "<s>" (first (first phrase)))
                    phrase
                    (recur (cons (choose-next-word context (take 3 phrase))
                                 phrase)))))))))
  )

(defn generate-rhyme
  ([context]
   (generate-rhyme context ["</s>"]))
  ([{:keys [perfect-rhyme-trie] :as context} phrase]
   (let [phrase1 (generate-sentence-backwards context phrase)
         rhyme (second (find-rhymes perfect-rhyme-trie (first (first (take-last 2 phrase1)))))
         phrase2 (generate-sentence-backwards context [(first rhyme) "</s>"])]
     [phrase1 phrase2])))

(comment
  (initialize)
  (generate-rhyme @context)

  (find-rhymes (@context :perfect-rhyme-trie) "technology")

  (let [{:keys [database trie rhyme-trie]} @context
        phrase ["</s>"]
        ids (map database phrase)]
    (get trie ids))
  (choose-next-word @context (take 3 [["</s>" 509]]))

  (generate-sentence-backwards @context ["</s>"])

  (valid-sentences? (generate-phrase @context '(["bitter" 41])))



  (choose-next-word @context (take 3 [["theology" 41]]))

  (choose-next-word @context [["and" 5] ["theology" 41]])

  (find-rhymes (@context :perfect-rhyme-trie) "theology")

  (trie/chil(trie/lookup (@context :trie) '(57 2477)))
  (take 5 (@context :trie))


  (->> (find-rhymes (@context :perfect-rhyme-trie) "technology")
       (map (fn [[word frq]]
              (let [n+1grams (word->n+1grams
                              (@context :trie)
                              (@context :database)
                              word)]
                (map vector n+1grams (repeat [word frq])))))
       (reduce into []))



















  (do
    #_(time
       (def backwards-trie
         (transduce (comp (xf-file-seq 0 250000)
                          (map slurp)
                          (map (partial n-to-m-backwards-grams 1 4))
                          (map (fn [ngrams] (map #(prep-ngram-for-trie %) ngrams)))
                          stateful-transducer)
                    conj
                    (file-seq (io/file "dark-corpus")))))

    #_(time
       (def tightly-packed-backwards-trie
         (tpt/tightly-packed-trie
          backwards-trie
          encode-fn
          (decode-fn @trie-database))))

    #_(tpt/save-tightly-packed-trie-to-file
       "resources/dark-corpus-backwards-tpt.bin"
       tightly-packed-backwards-trie)
    #_(with-open [wtr (clojure.java.io/writer "resources/backwards-database.bin")]
        (let [lines (->> (seq @trie-database)
                         (map pr-str)
                         (map #(str % "\n")))]
          (doseq [line lines]
            (.write wtr line))))

    (def loaded-backwards-trie
      (tpt/load-tightly-packed-trie-from-file
       "resources/dark-corpus-backwards-tpt.bin"
       (decode-fn @trie-database)))

    (def loaded-backwards-database
      (with-open [rdr (clojure.java.io/reader "resources/backwards-database.bin")]
        (into {} (map read-string (line-seq rdr)))))

    (def rhyme-database (atom {}))

    (def db
      (nippy/thaw-from-file (io/resource "dark-corpus-4-gram-backwards-db.bin")))

    (def perfect-rhyme-trie
      (transduce
       (comp
        (map first)
        (filter string?)
        (map #(vector % (reverse (word->phones %))))
        (map reverse))
       (completing
        (fn [trie [k v]]
          (update trie k (fnil #(update % 1 inc) [v 0]))))
       (trie/make-trie)
       @loaded-backwards-database))

    (def vowel-rhyme-trie
      (transduce
       (comp
        (map first)
        (filter string?)
        (map #(vector % (reverse (word->phones %))))
        (map reverse)
        (map (fn [[phones v]]
               [(map #(if (owoga.phonetics/vowel
                           (string/replace % #"\d" ""))
                        %
                        "?")
                     phones)
                v])))
       (completing
        (fn [trie [k v]]
          (update trie k (fnil #(update % 1 inc) [v 0]))))
       (trie/make-trie)
       (take 1000 db)))
    (take 20 vowel-rhyme-trie)
    )

  #_(with-open [wtr (clojure.java.io/writer "database.bin")]
      (let [lines (->> (seq @trie-database)
                       (map pr-str)
                       (map #(str % "\n")))]
        (doseq [line lines]
          (.write wtr line))))

  (profile
   {}
   (def example-story
     (loop [generated-text [(get @trie-database "<s>")]
            i              0]
       (if (> i 20)
         generated-text
         (let [children (loop [i 4]
                          (let [node     (p :lookup
                                            (trie/lookup
                                             loaded-tightly-packed-trie
                                             (vec (take-last i generated-text))))
                                children (p :seq-children (and node (trie/children node)))]
                            (cond
                              (nil? node)    (recur (dec i))
                              (< i 0)        (throw (Exception. "Error"))
                              (seq children) children
                              :else          (recur (dec i)))))]
           (recur
            (conj
             generated-text
             (->> children
                  (map #(get % []))
                  (remove nil?)
                  (#(p :weighted-selection (math/weighted-selection
                                            (fn [[_ c]] c)
                                            %)))
                  first))
            (inc i)))))))

  (->> example-story
       (map (fn [v] (get-in @trie-database [v])))
       (string/join " ")
       (#(string/replace % #" ([\.,\?])" "$1"))
       ((fn [txt]
          (string/replace txt #"(^|\. |\? )([a-z])" (fn [[a b c]]
                                                      (str b (.toUpperCase c)))))))

  (key-get-in-tpt
   tightly-packed-trie
   trie-database
   '("<s>" "<s>" "the"))
  ;; => {(2 2 3) {:value 3263, :count 462}}
  (id-get-in-tpt
   tightly-packed-trie
   trie-database
   '(2 2 3)))
  ;; => {("<s>" "<s>" "the") {:value ("<s>" "<s>" "the"), :count 462}}


(comment
  (->> (perfect-rhymes perfect-rhyme-trie
                       (or (dict/cmu-with-stress-map "technology")
                           (util/get-phones-with-stress "technology")))
       (map (comp first second))
       (remove nil?)
       #_#_#_#_(map @loaded-backwards-database)
       (map #(vector [%] (n+1grams
                          loaded-backwards-trie
                          [%])))
       (map (fn [[w1 w2s]]
              (mapv #(into w1 [(nth % 0)]) w2s)))
       (take 10))

  (->> (perfect-rhymes perfect-rhyme-trie
                       (or (dict/cmu-with-stress-map "technology")
                           (util/get-phones-with-stress "technology")))
       (map (comp first second))
       (remove nil?)
       (map @loaded-backwards-database)
       (map #(vector [%] (n+1grams
                          loaded-backwards-trie
                          [%])))
       (map (fn [[w1 w2s]]
              (mapv #(into w1 [(nth % 0)]) w2s)))
       (reduce into [])
       (map (fn [k]
              (let [children (->> (n+1grams loaded-backwards-trie k)
                                  (mapv first))]
                (mapv #(into k [%]) children))))
       (reduce into [])
       #_#_#_#_(map #(map @loaded-backwards-database %))
       (filter (partial every? dict/english?))
       (take 100)
       (map reverse))


  (util/get-phones-with-stress "you") ;; => ("B" "AA1" "DH" "ER" "M")
  (def phones (or (dict/cmu-with-stress-map "sandman")
                  (util/get-phones-with-stress "sandman")))

  (take 20 vowel-rhyme-trie)
  (->> (vowel-rhymes vowel-rhyme-trie phones)
       (map (comp first second))
       (remove nil?)
       (take 20))

  ;; Bigrams of rhyme
  (->> (perfect-rhymes perfect-rhyme-trie
                       (or (dict/cmu-with-stress-map "technology")
                           (util/get-phones-with-stress "technology")))
       (map (comp first second))
       (remove nil?)
       (map @loaded-backwards-database)
       (map #(vector [%] (n+1grams
                          loaded-backwards-trie
                          [%])))
       (map (fn [[w1 w2s]]
              (mapv #(into w1 [(nth % 0)]) w2s)))
       (reduce into [])
       (map (fn [k]
              (let [children (->> (n+1grams loaded-backwards-trie k)
                                  (mapv first))]
                (mapv #(into k [%]) children))))
       (reduce into [])
       (map #(map @loaded-backwards-database %))
       (filter (partial every? dict/english?))
       (take 100)
       (map reverse))

  )

(defn perfect-rhymes [rhyme-trie phones]
  (let [rhyme-suffix (first
                      (util/take-through
                       #(= (last %) \1)
                       (reverse phones)))]
    (trie/lookup rhyme-trie rhyme-suffix)))

(defn vowel-rhymes [rhyme-trie phones]
  (let [rhyme-suffix (->> (reverse phones)
                          (clone-consonants)
                          (util/take-through #(= (last %) \1))
                          (first))]
    (trie/lookup rhyme-trie rhyme-suffix)))

(defn rhymes-rank-1
  "Phones match from primary stress to the end."
  [trie phones]
  (let [rhyme-suffix (first
                      (util/take-through
                       #(= (last %) \1)
                       phones))]
    (trie/lookup trie rhyme-suffix)))

(defn rhymes-rank-2
  "Phones match from secondary stress to the end."
  [trie phones]
  (let [rhyme-suffix (first
                      (util/take-through
                       #(= (last %) \2)
                       phones))]
    (trie/lookup trie rhyme-suffix)))
