; Copyright 2023-2025 Nubank NA
; Copyright 2013 Relevance, Inc.
; Copyright 2014-2022 Cognitect, Inc.

; The use and distribution terms for this software are covered by the
; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0)
; which can be found in the file epl-v10.html at the root of this distribution.
;
; By using this software in any fashion, you are agreeing to be bound by
; the terms of this license.
;
; You must not remove this notice, or any other, from this software.

(ns io.pedestal.http.body-params
  "Provides interceptors and support for parsing the body of a request, generally according to
  the content type header.  This results in new keys on the request map, depending on the type
  of data parsed."
  (:require [clojure.edn :as edn]
            [cheshire.core :as json]
            [cheshire.parse :as parse]
            [io.pedestal.http.params :as pedestal-params]
            [io.pedestal.interceptor :refer [interceptor]]
            [cognitect.transit :as transit]
            [ring.middleware.params :as params])
  (:import (java.io EOFException InputStream InputStreamReader PushbackReader)
           (java.util.regex Pattern)))

(defn- search-for-parser
  [parser-map content-type]
  (reduce-kv (fn [_ k v]
               (when (re-find k content-type)
                 (reduced v)))
             nil
             parser-map))

(defn- parser-for
  "Find a parser for the given content-type, never returns nil"
  [parser-map content-type]
  (or
    (when content-type
      (search-for-parser parser-map content-type))
    identity))


(defn- parse-content-type
  "Runs the request through the appropriate parser"
  [parser-map request]
  (let [parser-fn (parser-for parser-map (:content-type request))]
    (parser-fn request)))

(defn add-parser
  "Adds a parser to the parser map; content type can either be a string (to exactly match the content type),
  or a regular expression.

  The parser-fn is passed the request map and returns a modified request map with an appropriate key and parsed value.
  For example, the default JSON parser adds a :json-params key."
  [parser-map content-type parser-fn]
  (let [content-pattern (if (instance? Pattern content-type)
                          content-type
                          (re-pattern (str "^" (Pattern/quote content-type) "$")))]
    (assoc parser-map content-pattern parser-fn)))

(defn custom-edn-parser
  "Return an edn-parser fn that, given a request, will read the body of that
  request with `edn/read`. options are key-val pairs that will be passed as a
  hash-map to `edn/read`."
  [& options]
  (let [edn-options (merge {:eof nil}
                           (apply hash-map options))]
    (fn [request]
      (let [encoding     (or (:character-encoding request) "UTF-8")
            input-stream (InputStreamReader.
                           ^InputStream (:body request)
                           ^String encoding)]
        (assoc request
               :edn-params (->
                             input-stream
                             PushbackReader.
                             (->> (edn/read edn-options))))))))

(defn- json-read
  "Parse json stream, supports parser-options with key-val pairs:

    :bigdec Boolean value which defines if numbers are parsed as BigDecimal
            or as Number with simplest/smallest possible numeric value.
            Defaults to false.
    :key-fn Key coercion, where true coerces keys to keywords, false leaves
            them as strings, or a function to provide custom coercion.
    :array-coerce-fn Define a collection to be used for array values by name."
  [reader & options]
  (let [{:keys [bigdec key-fn array-coerce-fn]
         :or   {bigdec          false
                key-fn          keyword
                array-coerce-fn nil}} options]
    (binding [parse/*use-bigdecimals?* bigdec]
      (json/parse-stream (PushbackReader. reader) key-fn array-coerce-fn))))

(defn custom-json-parser
  "Return a json-parser fn that, given a request, will read the body of the
  request with `json/parse-stream`. options are key-val pairs that will be passed along
  to `json/parse-stream`."
  [& options]
  (fn [request]
    (let [encoding (or (:character-encoding request) "UTF-8")]
      (assoc request
             :json-params
             (apply json-read
                    (InputStreamReader.
                      ^InputStream (:body request)
                      ^String encoding)
                    options)))))

(defn custom-transit-parser
  "Return a transit-parser fn that, given a request, will read the
  body of that request with `transit/read`. options is a sequence to
  pass to transit/reader along with the body of the request."
  [& options]
  (fn [{:keys [body] :as request}]
    ;; Alas, exceptions are a poor form of flow control, but
    ;; the prior check, via InputStream/available, was not always accurate
    ;; (see https://github.com/pedestal/pedestal/issues/764).
    ;; Fortunately, this code is only invoked when the request's content type
    ;; identifies transit and it should be quite rare for a client to do so and
    ;; provide an empty body.
    (let [transit-params (try
                           (transit/read (apply transit/reader body options))
                           ;; com.cognitect.transit.impl.ReaderFactory/read catches
                           ;; the EOFException and rethrows it, wrapped in a RuntimeException.
                           (catch RuntimeException e
                             (when-not (some->> e ex-cause (instance? EOFException))
                               (throw e))

                             nil))]
      (cond-> request
        transit-params (assoc :transit-params transit-params)))))


(defn form-parser
  "Take a request and parse its body as a form."
  [request]
  (let [encoding (or (:character-encoding request) "UTF-8")
        request  (params/assoc-form-params request encoding)]
    (update request :form-params pedestal-params/keywordize-keys)))

(defn default-parser-map
  "Return a map of MIME-type to parsers. Included types are edn, json and
  form-encoding. parser-options are key-val pairs, valid options are:

    :edn-options A hash-map of options to be used when invoking `edn/read`.
    :json-options A hash-map of options to be used when invoking `json/parse-stream`.
    :transit-options A vector of options to be used when invoking `transit/reader` - must apply to both json and msgpack

  Examples:

  (default-parser-map :json-options {:key-fn keyword})
  ;; This parser-map would parse the json body '{\"foo\": \"bar\"}' as
  ;; {:foo \"bar\"}

  (default-parser-map :edn-options {:readers *data-readers*})
  ;; This parser-map will parse edn bodies using any custom edn readers you
  ;; define (in a data_readers.clj file, for example.)

  (default-parser-map :transit-options [{:handlers {\"custom/model\" custom-model-read-handler}}])
  ;; This parser-map will parse the transit body using a handler defined by
  ;; custom-model-read-handler."
  [& parser-options]
  (let [{:keys [edn-options json-options transit-options]} (apply hash-map parser-options)
        edn-options-vec  (apply concat edn-options)
        json-options-vec (apply concat json-options)]
    (-> {}
        (add-parser #"^application/edn" (apply custom-edn-parser edn-options-vec))
        (add-parser #"^application/json" (apply custom-json-parser json-options-vec))
        (add-parser #"^application/x-www-form-urlencoded" form-parser)
        (add-parser #"^application/transit\+json" (apply custom-transit-parser :json transit-options))
        (add-parser #"^application/transit\+msgpack" (apply custom-transit-parser :msgpack transit-options)))))

(defn body-params
  "Returns an interceptor that will parse the body of the request according to the content type.
  The normal rules are provided by [[default-parser-map]] which maps a regular expression identifying a content type
  to a parsing function for that type.

  Parameters parsed from the body are added as a new key to the request map dependending on
  which parser does the work; for the default parsers, one of the following will be used:

  - :json-params
  - :edn-params
  - :form-params
  - :transit-params
  "
  ([] (body-params (default-parser-map)))
  ([parser-map]
   (interceptor
     {:name  ::body-params
      :enter (fn [context]
               (update context :request #(parse-content-type parser-map %)))})))

