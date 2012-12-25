(ns torrent-client.client.core.pieces
  (:use 
    [torrent-client.client.core.crypt :only [sha1 byte-array->str]]
    [torrent-client.client.core.byte-array :only [uint8-array]]))

(deftype PieceFile [meta file]

  Object
  (toString [this]
    (pr-str this))

  IWithMeta
  (-with-meta [this meta] (PieceFile. meta file))

  IMeta
  (-meta [this] meta)

  ILookup
  ; Does this file contain a given block index
  (-lookup [this k]
    (-lookup this k nil))
  (-lookup [this k not-found]
    (-contains-key? this k))

  Fn
  IFn
  (-invoke [this]
    file)
  (-invoke [this k]
    (-lookup this k))
  (-invoke [this k not-found]
    (-lookup this k not-found))

  IAssociative
  (-contains-key? [this k]
    "Check if a block-index is required by this file"
    (if-not (nil? meta)
      (<= (meta :block-start) k (meta :piece-end))))

  IHash
  (-hash [o]
    (goog.getUid o))

  )

(defn piece-file [file]
  (PieceFile. nil file))

(deftype Piece [meta byte-array ^:mutable __hash]

  ICounted
  (-count [a] (count byte-array))

  IHash
  (-hash [_]
    ; H.C; check caching-hash macro
    (if-not (nil? __hash)
      __hash
      ; sha1 does not return a Uint8Array, it returns a regular array
      (let [hash-str (byte-array->str (sha1 byte-array))]
        ; H.C; this causes a compile fail?
        ; (set! __hash hash-str)

        ; If the hash didn't previously exist generate it and
        hash-str)))

  IWithMeta
  (-with-meta [_ meta] (Piece. meta byte-array __hash))

  IMeta
  (-meta [_] meta)

  )

(defn piece 
  "Build a piece from its component blocks"
  [blocks]
  (let [blocks (sort :begin blocks)
        piece-size (reduce + (map (comp count :data) blocks))
        ; Build a byte array long enough for all the blocks
        byte-array (uint8-array piece-size)]
    ; Then add all the pieces at their correct offset
    (doseq [block blocks]
      (.set byte-array (block :data) (block :begin)))
    (Piece. nil byte-array nil)))

(.log js/console "hi")