(ns torrent-client.protocol.bittorrent
  (:require
    [torrent-client.core.crypt :as crypt]
    [torrent-client.core.bencode :as bencode]
    [torrent-client.core.reader :as reader]
    [torrent-client.bitfield :as bitfield]
    [torrent-client.protocol.main :as protocol]
    [goog.events :as events])
  (:use 
    [torrent-client.core.byte-array :only [uint8-array subarray]]
    [torrent-client.peer-id :only [peer-id]]
    [waltz.state :only [trigger]]))

(defn ^boolean array-buffer-view? [candidate]
  ; Incorrect; but js/ArrayBufferView doesn't exist
  (instance? js/Uint8Array candidate))

(deftype Char [code]
  Object
  (toString [this]
    (.fromCharCode js/String code))

  IEquiv
  (-equiv [_ o]
    (cond
      ; If a 1 character byte array compare the first bytes
      (array-buffer-view? o) (and (= (count o) 1) (= (first o) code))
      ; compare string char codes
      (string? o) (= o (str code))
      :else (= o code)))

  IHash
  (-hash [this] 
    (goog.string/hashCode (pr-str this)))

  ICounted
  (-count [array] 1)
  )

(defn char [code]
  (Char. code))

; H.C switch over to \x05
(def msg-choke (char 00))
(def msg-unchoke (char 01))
(def msg-interested (char 02))
(def msg-not-interested (char 03))
(def msg-have (char 04))
(def msg-bitfield (char 05))
(def msg-request (char 06))
(def msg-piece (char 07))
(def msg-cancel (char 8))
; ; The length of the string "BitTorrent protocol"
(def msg-handshake (char 19))
(def msg-extended (char 20))

; handshake indicated by id of 0 (no extension can have this id)
(def extended-handshake 0)
(def ut-metadata 3)
; msg_type codes for the ut-metadata extension
(def ut-metadata-request 0)
(def ut-metadata-piece 1)
(def ut-metadata-reject 2)

; map extension ids to a keyword
(def extensions 
  {extended-handshake :extended-handshake
   ut-metadata :ut-metadata})

;;************************************************
;; Map received extensions based on their
;; extension and msg_type
;;************************************************

(defmulti receive-extension (fn [peer extension & [message data]]
  (if-let [msg-type (message :msg_type)]
    [extension (message :msg_type)]
    extension)))

(defmethod receive-extension extended-handshake [p _ message]
  (trigger p :receive-extended message))

(defmethod receive-extension [ut-metadata ut-metadata-request] [p _ message]
  (trigger p :receive-metadata-request (message :piece)))

(defmethod receive-extension [ut-metadata ut-metadata-piece] [p _ message data]
  (trigger p :receive-metadata-piece (message :piece) data))

(defmethod receive-extension [ut-metadata ut-metadata-reject] [p _ message]
  (trigger p :receive-metadata-reject (message :piece)))

;;************************************************
;; Map incoming data based on it's first byte
;; and co-ordinate with the peer with the data
;;************************************************

(defmulti receive-data (fn [peer data] 
  (char (first data))))

(defmethod receive-data msg-extended [p data]
  "When given an extension pass it off to a further multimethod"
  (let [extension (second data)
        reader (reader/push-back-reader (subarray data 2))
        [message data] (bencode/decode reader :payload)]
    (receive-extension p extension message data)))

(defmethod receive-data msg-choke [p _]
  (trigger p :receive-choke))

(defmethod receive-data msg-unchoke [p _]
  (trigger p :receive-unchoke))

(defmethod receive-data msg-interested [p _]
  (trigger p :receive-interested))
(defmethod receive-data msg-not-interested [p _] 
  (trigger p :receive-not-interested))

(defmethod receive-data msg-have [p data]
  (let [data (crypt/unpack [:int] (rest data))]
    (trigger p :receive-have data)))

(defmethod receive-data msg-bitfield [p data]
  (trigger p :receive-bitfield (bitfield/bitfield (subarray data 1))))

(defmethod receive-data msg-request [p data]
  (let [[index begin length] (crypt/unpack [:int :int :int] (subarray data 1))]
    (trigger p :receive-request index begin length)))

(defmethod receive-data msg-piece [p data]
  (let [[index begin] (crypt/unpack [:int :int] (subarray data 1 9))
        piece (subarray data 9)]
    (trigger p :receive-block index begin piece)))

(defmethod receive-data msg-cancel [p data]
  (let [[index begin length] (crypt/unpack [:int :int :int] (rest data))]
    (trigger p :receive-cancel index begin length)))

(defmethod receive-data :default [p data]
  (let [reserved (bitfield/bitfield (subarray data 20 28))
        info-hash (vec (subarray data 28 48))
        peer-id (crypt/byte-array->str (vec (subarray data 48 68)))]
    (trigger p :receive-handshake reserved info-hash peer-id)))

;;************************************************
;; The bittorrent protocol
;;************************************************

(deftype BittorrentProtocol [torrent channel]
  protocol/Protocol

  (send-data [client string]
    (.log js/console "sending data" (aget channel "readyState") string)
    (.send channel string))

  (send-data [client type data]
    (cond
      (nil? data)
      (protocol/send-data client (str type))

      (string? data)
      (protocol/send-data client (str type data))

      :else
      (let [; Turn our data into a vector if it isn't
            data (if (vector? data) data (vector data))
            ; Concat all our data with the type at the start
            string (str type (apply str data))]
        (protocol/send-data client string))))
    
  ; H.C commented out for the legacy demo
  ; (send-data [client & data]
  ;   (if (string? (second data))
  ;     (protocol/send-data client (apply str data))
  ;     (let [; Build a buffer big enough to hold our data
  ;           ; only count byte arrays, add one for the msg-type
  ;           buffer-size (inc (reduce + (map count (rest data))))
  ;           byte-array (uint8-array buffer-size)]
  ;       (.set byte-array [(first data)])
  ;       (loop [data (rest data)
  ;              offset 1]
  ;         (if-let [item (first data)]
  ;           (do
  ;             ; Add all the data to the buffer at the correct offset
  ;             (.log js/console item offset)
  ;             (.set byte-array item offset)
  ;             (recur (rest data) (+ offset (count item))))
  ;           ; Finally send the buffer
  ;           (protocol/send-data client (.-buffer byte-array)))))
  ;     ))

  (send-handshake [client]
    "Generate a handshake string"
    (let [protocol-name "BitTorrent protocol"
          reserved (crypt/byte-array->str [00 00 00 00 00 10 00 00])
          info-hash (crypt/byte-array->str (@torrent :info-hash))
          data (str protocol-name reserved info-hash @peer-id)]
      (protocol/send-data client msg-handshake data)))

  (send-extended [client id message] 
    (protocol/send-extended client id message nil))

  (send-extended [client id message data] 
    (let [id (if (keyword? id) (get extensions id) id)
          body (crypt/byte-array->str (bencode/encode message))
          data (str (char id) body data)]
      (protocol/send-data client msg-extended data)))

  (send-extended-handshake [client]
    (let [message {:m extensions :metadata_size (@torrent :info-length)}]
      (protocol/send-extended client extended-handshake message)))

  (send-metadata-request [client piece-index]
    (let [message {:msg_type ut-metadata-request :piece piece-index}]
      (protocol/send-extended client ut-metadata message)))

  (send-metadata-piece [client piece-index info-length data]
    (let [message {:msg_type ut-metadata-piece 
                   :piece piece-index 
                   :metadata_size info-length}]
      (protocol/send-extended client ut-metadata message data)))

  (send-metadata-reject [client piece-index]
    (let [message {:msg_type ut-metadata-reject :piece piece-index}]
      (protocol/send-extended client ut-metadata message)))

  (send-choke [client]
    (protocol/send-data client msg-choke ""))

  (send-unchoke [client]
    (protocol/send-data client msg-unchoke ""))

  (send-interested [client]
    (protocol/send-data client msg-interested ""))

  (send-not-interested [client]
    (protocol/send-data client msg-not-interested ""))

  (send-have [client index]
    (let [data (crypt/pack :int index)]
      (protocol/send-data client msg-have data)))

  (send-bitfield [client]
    (let [byte-array (.-byte-array (@torrent :bitfield))]
      (protocol/send-data client msg-bitfield byte-array)))

  (send-request [client piece-index begin length]
    ; (js* "debugger;")
    (let [data (crypt/pack :int piece-index :int begin :int length)]
      (protocol/send-data client msg-request data)))

  ; H.C REVIEW
  (send-block [client piece-index begin piece]
    (let [data (crypt/pack :int piece-index :int begin)]
      ; (js* "debugger;")
      (protocol/send-data client msg-piece [data piece])))

  (send-cancel [client index begin length]
    (let [data (crypt/pack :int index :int begin :int length)]
      (protocol/send-data client msg-cancel data)))

  )

(defn generate-protocol [torrent channel]
  "Generate an instance of the protocol"
  (BittorrentProtocol. torrent channel))