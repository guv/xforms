(ns net.cgrand.xforms.io
  (:require [clojure.java.io :as io]
            [clojure.java.shell :as sh]
            [clojure.edn :as edn])
  (:import (java.io Reader BufferedReader IOException InputStream OutputStream BufferedWriter Writer PushbackReader InputStreamReader OutputStreamWriter Closeable)
           (java.util Arrays List)
           (java.util.concurrent ArrayBlockingQueue)
           (java.lang ProcessBuilder$Redirect)
           (clojure.lang IFn Fn IReduce)))

(defn keep-opts [m like]
  (let [ns (namespace like)]
    (into {}
      (keep (fn [[k v]]
              (when (= ns (or (namespace k) ns))
                [(keyword (name k)) v])))
      m)))

(defn lines-in
  "Returns a reducible view over the provided input.
   Input is read line by line. Coercion of the input is done by io/reader (and opts are passed to it).
   Input is automatically closed upon completion or error."
  [in & opts]
  (let [no-init (Object.)]
    (reify IReduce
      (reduce [self f] (.reduce self f no-init))
      (reduce [self f init]
        (with-open [^Reader rdr (apply io/reader in opts)]
          (let [^BufferedReader rdr (cond-> rdr (not (instance? BufferedReader rdr)) (BufferedReader.))
                init (if (identical? init no-init)
                       (or (.readLine rdr) (f))
                       init)]
            (loop [state init]
              (if-some [line (.readLine rdr)]
                (let [state (f state line)]
                  (if (reduced? state)
                    (unreduced state)
                    (recur state)))
                state))))))))

(defn lines-out
  "1-2 args: reducing function that writes values serialized to its accumulator (a java.io.BufferedWriter).
   3+ args: transducing context that writes transformed values to the specified output. The output is
   coerced to a BufferedWriter by passing out and opts to clojure.java.io/writer. The output is automatically closed.
   Returns the writer."
  ([w] w)
  ([^BufferedWriter w line]
    (doto w
      (.write (str line))
      (.newLine)))
  ([out xform coll & opts]
    (with-open [^Writer w (apply io/writer out opts)]
      (transduce xform lines-out w coll))))

(defn edn-in
  "Returns a reducible view over the provided input.
   Input is read form by form. Coercion of the input is done by io/reader.
   Input is automatically closed upon completion or error.
   Unqualified options are passed to both edn/read and io/writer, options qualified by clojure.java.io
   are only passed (once dequalified) to io/writer, options qualified by clojure.edn are only passed to
   edn/read"
  [in & {:as opts}]
  (let [no-init (Object.)]
    (reify IReduce
     (reduce [self f] (.reduce self f no-init))
     (reduce [self f init]
       (with-open [^Reader rdr (apply io/reader in (mapcat seq (keep-opts opts ::io/opts)))]
         (let [^BufferedReader rdr (cond-> rdr (not (instance? PushbackReader rdr)) PushbackReader.)
               opts (assoc (keep-opts opts ::edn/opts) :eof no-init)
               init (if (identical? init no-init)
                      (let [form (edn/read opts rdr)]
                        (if (identical? no-init form)
                          (f)
                          form))
                      init)]
           (loop [state init]
             (let [form (edn/read opts rdr)]
               (if (identical? no-init form)
                 state
                 (let [state (f state form)]
                   (if (reduced? state)
                     (unreduced state)
                     (recur state))))))))))))

(defn edn-out
  "1-2 args: reducing function that writes values serialized as EDN to its accumulator (a java.io.Writer).
   3+ args: transducing context that writes transformed values to the specified output. The output is
   coerced to a Writer by passing out and opts to clojure.java.io/writer. The output is automatically closed.
   Returns the writer."
  ([w] w)
  ([^Writer w x]
    (binding [*out* w
              *print-length* nil
              *print-level* nil
              *print-dup* false
              *print-meta* false
              *print-readably* true]
      (prn x)
      w))
  ([out xform coll & opts]
    (with-open [^Writer w (apply io/writer out opts)]
      (transduce xform edn-out w coll))))

(defn- stream-spec [x]
  (into {:mode :lines :enc "UTF-8"}
    (cond (map? x) x (string? x) {:enc x} (keyword? x) {:mode x})))

(defn sh
  "Transducer or reducible view (in this case assumes empty stdin).
   Spawns a process (program cmd with optional arguments arg1 ... argN) and pipes data through it.
   Options may be:
    * :env, an environment variables map, it will be merged with clojure.java.shell/*sh-env* and JVM environment (in decreasing precedence order),
    * :dir, the current dir (defaults to clojure.java.shell/*sh-dir* or JVM current dir),
    * :in and :out which are maps with keys :mode (:lines (default), :text or :bytes) and :enc (defaults to \"UTF-8\");
      encoding applies only for modes :lines or :text; shorthands exist: a single keyword is equivalent to {:mode k :enc \"UTF-8\"},
      a single string is equivalent to {:mode :lines, :enc s}.
   In :bytes mode, values are bytes array.
   In :lines mode, values are strings representing lines without line delimiters.
   In :text mode, values are strings."
  {:arglists '([cmd arg1 ... argN & opts])}
  [& args]
  (reify
    IReduce
    (reduce [self rf]
      (reduce rf (eduction self nil))) ; quick way to handle no init
    (reduce [self rf init]
      (let [xf (self rf)]
        (xf init)))
    Fn
    IFn
    (invoke [_ rf]
      (let [[cmd [& {:as opts :keys [env in out dir] :or {dir sh/*sh-dir*}}]] (split-with string? args)
            env (into (or sh/*sh-env* {}) env)
            env (into {} (for [[k v] env] [(name k) (str v)]))
            proc (-> ^List (map str cmd) ProcessBuilder.
                   (.redirectError ProcessBuilder$Redirect/INHERIT)
                   (doto (-> .environment (.putAll env)))
                   (.directory (io/as-file dir))
                   .start)
            EOS (Object.)
            q (ArrayBlockingQueue. 16)
            drain (fn [acc]
                    (loop [acc acc]
                      (if-some [x (.poll q)]
                        (let [acc (if (identical? EOS x) (reduced acc) (rf acc x))]
                          (if (reduced? acc)
                            (do
                              (.destroy proc)
                              acc)
                            (recur acc)))
                        acc)))
            in (stream-spec in)
            out (stream-spec out)
            ^Closeable stdin (cond-> (.getOutputStream proc) (#{:lines :text} (:mode in)) (-> (OutputStreamWriter. ^String (:enc in)) BufferedWriter.))
            stdout (cond-> (.getInputStream proc) (#{:lines :text} (:mode out)) (-> (InputStreamReader. ^String (:enc out)) BufferedReader.))
            write!
            (case (:mode in)
              :lines
              (fn [x]
                (doto ^BufferedWriter stdin
                  (.write (str x))
                  .newLine))
              :text
              (fn [x]
                (.write ^BufferedWriter stdin (str x)))
              :bytes
              (fn [^bytes x]
                (.write ^OutputStream stdin x)))]
        (-> (case (:mode out)
              :lines
              #(loop []
                 (if-some [s (.readLine ^BufferedReader stdout)]
                   (do (.put q s) (recur))
                   (.put q EOS)))
              :text
              #(let [buf (char-array 1024)]
                 (loop []
                   (let [n (.read ^BufferedReader stdout buf)]
                     (if (neg? n)
                       (.put q EOS)
                       (do (.put q (String. buf 0 n)) (recur))))))
              :bytes
              #(let [buf (byte-array 1024)]
                 (loop []
                   (let [n (.read ^InputStream stdout buf)]
                     (if (neg? n)
                       (.put q EOS)
                       (do (.put q (Arrays/copyOf buf n)) (recur)))))))
          Thread. .start)
        (fn
          ([] (rf))
          ([acc]
            (.close stdin)
            (loop [acc acc]
              (let [acc (drain acc)]
                (if (reduced? acc)
                  (rf (unreduced acc))
                  (recur acc)))))
          ([acc x]
            (let [acc (drain acc)]
              (try
                (when-not (reduced? acc)
                  (write! x))
                acc
                (catch IOException e
                  (ensure-reduced acc))))))))))