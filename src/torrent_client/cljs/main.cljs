(ns torrent-client.cljs.main
  (:require 
    [torrent-client.cljs.core.dispatch :as dispatch]
    [torrent-client.cljs.bitfield :as bitfield]
    [goog.events :as events]
    [goog.events.FileDropHandler :as FileDropHandler]
    [goog.Uri :as Uri]
    [goog.Uri.QueryData :as QueryData]
    [goog.string :as gstring]
    [clojure.string :as string]
    [waltz.state :as state]
    [crate.core :as crate]
    [crate.form :as form]
    [clojure.browser.repl :as repl])
  (:use
    [jayq.core :only [$ on attr document-ready empty text val prepend css]]
    [torrent-client.cljs.jayq :only [append input-files event-files modal tab]]
    [torrent-client.cljs.waltz :only [machine]]
    [torrent-client.cljs.files :only [files]]
    [torrent-client.cljs.torrent :only [share-torrent]]
    [torrent-client.cljs.torrents :only [torrents]]
    [crate.binding :only [bound]]
    [goog.format :only [numBytesToString]])
  (:use-macros
    [async.macros :only [async let-async]]
    [waltz.macros :only [in out defstate defevent]]
    [crate.def-macros :only [defpartial]]))

;;************************************************
;; Dev stuff
;;************************************************

; [noir.cljs.client.watcher :as watcher]
; (watcher/init)
(repl/connect "http://localhost:9000/repl")

;;************************************************
;; Atoms & state
;;************************************************

; A vector of files currently being used in the create form
(def create-form-files (atom []))

;;************************************************
;; jquery selectors
;;************************************************

(def $document ($ js/document))
(def $window ($ js/window))
(def $body ($ "body"))
(def $add-modal ($ "#add-modal"))
(def $add-form ($ "#add-form"))
(def $create-modal ($ "#create-modal"))
(def $create-form ($ "#create-form"))
(def $alerts ($ "#alerts"))
(def $torrents ($ "tbody"))
(def $demo-torrent ($ "#demo-torrent"))

;;************************************************
;; Adding metainfo and files
;;************************************************

(defn metainfo-file?
  "Return true if a file is metainfo file"
  [file]
  ; The proper way to detect torrents is to sniff the type
  (or (= (.-type file) "application/x-bittorrent")
      ; But my ubuntu chrome build returns blank
      (and (= (.-type file) "")
           (gstring/caseInsensitiveEndsWith (.-name file) ".torrent"))))

; Set up drag and drop for the page
(let [dropzone js/document
      handler (events/FileDropHandler. dropzone true)]
  ; Listen for any files dropped on the window
  (events/listen handler (-> events/FileDropHandler .-EventType .-DROP) (fn [e]
    (let [files (event-files e)
          ; check out how many .torrent files we have here
          metainfo (vec (filter metainfo-file? files))]
      (cond
        ; If just given torrents, download them
        (and (= (count files) (count metainfo)))
        (doseq [file files]
          (dispatch/fire :add-metainfo-file file))

        ; Given no .torrents, just files to create a torrent
        (= (count metainfo) 0)
        (dispatch/fire :add-torrent-files files)

        :else
        (dispatch/fire :error "To load an existing .torrent use \"add torrent\""))))))

(dispatch/react-to #{:add-torrent-files} (fn [_ files]
  (.modal $add-modal "hide")
  (.modal $create-modal "show")
  ; Add all our files to create-form
  (apply (partial swap! create-form-files conj) files)
  ; And render them
  ; H.C: could this be done through an atom watch?
  (doseq [file files]
    (append ($ ".files") (torrent-file-badge file)))))

(dispatch/react-to #{:add-metainfo-file} (fn [_ _]
  (.modal $create-modal "hide")
  (.modal $add-modal "hide")))

(dispatch/react-to #{:share-torrent} (fn [_ torrent]
  (.modal $add-modal "hide")
  (.modal $create-modal "hide")
  ; See the magnet spec for an explanation of these values
  ; http://en.wikipedia.org/wiki/Magnet_URI_scheme
  (let [base-url "http://hcliff.github.com/ampere"
        querystring 
         {:xt (str "urn:btih:" (@torrent :pretty-info-hash))
          :dn (@torrent :name)}
        querydata (QueryData/createFromMap (clj->js querystring))
        ; preserve special characters (: /)
        querystring (.toDecodedString querydata)
        magnet-url  (.setQueryData (Uri/parse base-url) querystring)

        ; add all the trackers
        ; H.C stupid convention of duplicate keys exists (check any torrent site)
        add-tracker #(str %1 "&tr=" %2)
        magnet-url (reduce add-tracker magnet-url (@torrent :announce-list))

        ; Render the modal dialog
        modal-content 
         {:magnet-url magnet-url
          ; :torrent-file torrent-file
          :name (@torrent :name)}
        $share-modal ($ (share-modal modal-content))]
    ; Unlike other modals there can be multiple built modals
    ; so it must be created as a partial
    (prepend $body $share-modal)
    ; highlight the whole link when the user clicks it
    (on ($ "input" $share-modal) :click (fn [e]
      (.select ($ (.-currentTarget e)))))
    (.modal $share-modal "show"))))

(on $create-form :submit (fn [e]
  (.preventDefault e)
  (.log js/console "tracker" (val ($ "[name=tracker]" $create-form)))
  (dispatch/fire :create-torrent {
    :name (val ($ "[name=name]" $create-form))
    :tracker (val ($ "[name=tracker]" $create-form))
    :files @create-form-files})
  (modal $create-modal "hide")
  (let [building-alert (alert "Building your file...")]
    (append $alerts building-alert))))

(on $create-modal :hide (fn [e]
  ; Clear input values
  (.reset (first $create-form))
  ; And reset the files
  (empty ($ ".files" $create-modal))
  (reset! create-form-files [])))

(on $add-form :submit (fn [e]
  (.preventDefault e)
  ; Get the files given to us
  (let [file (first (input-files ($ "[name=metainfo]" $add-form)))]
    (dispatch/fire :add-metainfo-file file)
  (modal ($ "#add-modal") "hide"))))

(on $add-modal :hide (fn [e]
  (.reset (first $add-form))))

(on $demo-torrent :click (fn [e]
  "When the user clicks the demo, download the .torrent and use it"
  (.preventDefault e)
  (let [url (attr $demo-torrent :href)
        options {:xhrFields {:responseType "arraybuffer"} :dataType "binary"}]
    ; Hide the demo, no longer relevant
    (css $demo-torrent :display "none")
    ; Given a .torrent URL, download it then use it
    (let-async [file (ajax-binary url options)]
      (dispatch/fire :add-metainfo-byte-array file)))))

; jQuery doesn't support binary requests
(defn ajax-binary [url settings]
  (async [success-callback]
    (let [xhr (js/XMLHttpRequest.)]
      (set! (.-responseType xhr) "arraybuffer")
      (set! (.-onload xhr) (fn [e]
        (this-as self
          (if (= 200 (.-status self))
            (success-callback (.-response self))))))
      (.open xhr "GET" url true)
      (.send xhr))))

;;************************************************
;; Template methods
;;************************************************

(defn bound-class 
  "Given a func that returns boolean display a css class"
  [torrent func class-name & [negative-class-name]]
  (letfn [(which-class [torrent]
            (if (func torrent)
              class-name
              negative-class-name))]
    (bound torrent which-class)))

(defn download-percent 
  "Rapidly determine the download progress by examining the
  bitfield completion, not 100% accurate due to variable
  last block size, but good enough"
  [torrent]
  (let [percent (/ (torrent :pieces-written) (torrent :pieces-length))]
    (str (/ percent 0.01) "%")))

(defn total-length-to-string [{:keys [total-length]}]
  (string/lower-case (numBytesToString total-length)))

(defn time-remaining-to-string [torrent]
  "∞"
  ; (if (paused? torrent)
  ;   "∞"
  ;   (let [; The number of seconds remaining
  ;         seconds (/ (torrent :total-length) (* 700 1000))]
  ;     seconds))
)

(defn torrent-speed-to-string [torrent]
  ""
  )

(defn file-url [file]
  "Given a file return a link to it's localstorage entry"
  (.toURL (.-file file)))

;;************************************************
;; Templates
;;************************************************

(defpartial alert [content]
  [:div.alert
    [:button.close {:type "button" :data-dismiss "alert"} "×"]
    content])

(defpartial torrent-file-badge [content]
  [:span.label (.-name content)])

(defpartial share-modal [content]
  [:div.modal
    [:div.modal-header
      [:h3 (str (content :name) " is ready to share!")]
      [:button.close {:type "button" :data-dismiss "modal"}]]
    [:form#create-form.modal-body.form-horizontal
      [:div.control-group
        (form/label {:class "control-label"} "link" "download link")
        [:div.controls
          (form/text-field {:value (content :magnet-url) :class "input-xlarge"} "link")]]
      ; [:div.control-group
      ;   [:div.controls
      ;     [:a#built-download 
      ;      {:download (get-in content [:torrent-file .-name])
      ;       :title (str "download " (get-in content [:torrent-file .-name]))
      ;       :href (file-url (content :torrent-file))}
      ;       "or download the .torrent"]]]
            ]
    [:div.modal-footer
      [:a.btn {:data-dismiss "modal"} "close"]]])

(defpartial torrent-row [torrent]
  [:tr
    [:td.flex2.name (@torrent :name)]
    [:td.flex1.size (total-length-to-string @torrent)]
    [:td.flex5.progress-td
      [:div {:class (bound-class torrent active? 
                      "progress progress-striped active"
                      "progress progress-striped")}
        [:div.bar {:style 
          {:width (bound torrent download-percent)}}]
        ; [:label {:class (bound-class torrent active? "label" "label hide")} 
        ;   (bound torrent time-remaining-to-string)]
      ]]
    [:td.flex1.speed (bound torrent torrent-speed-to-string)]
    [:td.actions
      [:div.btn-group
        [:button.btn
          [:i.icon-globe]]
        [:button.btn {:disabled true}
          [:i {:class (bound-class torrent active? "icon-pause" "icon-play")}]]
        [:a {:href (file-url (first (@files (@torrent :pretty-info-hash))))
             :target "_blank" 
             :class (bound-class torrent completed? "btn" "btn hide")}
          [:i.icon-folder-open]]
        [:button.btn {:disabled true}
          [:i.icon-trash]]
      ]]])

;;************************************************
;; Rendering content
;;************************************************

(def elements (atom {}))

(dispatch/react-to #{:started-torrent} (fn [_ torrent]
  (let [element (torrent-row torrent)]
    (on ($ "i.icon-globe" element) :click (fn [_]
      (dispatch/fire :share-torrent torrent)))
    ; Render the torrent row and add it to the atom
    (swap! elements assoc (@torrent :pretty-info-hash) element)
    (append $torrents element))))

(defn active? [torrent]
  "Take either a collection or atom and return it's active status"
  (if-not (coll? torrent)
    (active? @torrent)
    true
    ; (= :processed (torrent :status))
    ))

(def paused? (complement active?))

(defn completed? [torrent]
  "Take a collection or atom and determin if the torrent has finished"
  (if-not (coll? torrent)
    (completed? @torrent)
    (>= (torrent :pieces-written) (torrent :pieces-length))))

(def downloading? (complement completed?))

(dispatch/react-to #{:started-torrent :completed-torrent :stopped-torrent 
                     :paused-torrent :resumed-torrent} (fn [_ torrent]
  (let [; Only show splings that are not paused
        torrents (filter active? (vals @torrents))
        ; Count the splings downloading (file size less than target size)
        downloading (count (filter downloading? torrents))
        ; If it's active and downloading
        completed (count (filter completed? torrents))]
    (text ($ "#downloading-count") downloading)
    (text ($ "#completed-count") completed))))

(defn tab-machine []
  (let [me (machine {:label :tab-machine :current :downloading})]

    ; When a torrent finishes automatically show the completed tab
    (dispatch/react-to #{:completed-torrent :built-torrent} 
      #(state/set me :completed))

    (on ($ "#downloading-tab") :click #(state/set me :downloading))
    (on ($ "#completed-tab") :click #(state/set me :completed))
    (on ($ "#settings-tab") :click #(state/set me :settings))

    (defstate me :downloading
      (in []
        ; (js* "debugger;")
        (empty $torrents)
        (let [torrents (filter downloading? (vals @torrents))
              elements (map (comp @elements :pretty-info-hash deref) torrents)]
          (append $torrents (doall elements)))
        (tab ($ "#downloading-tab") "show")))

    (defstate me :completed
      (in []
        ; (js* "debugger;")
        (empty $torrents)
        (let [torrents (filter completed? (vals @torrents))
              elements (map (comp @elements :pretty-info-hash deref) torrents)]
          (append $torrents (doall elements)))
        (tab ($ "#completed-tab") "show")))

    (defstate me :settings
      (in []
        (tab ($ "#settings-tab") "show")))

    ))

(tab-machine)

;************************************************
; Client notifications
;************************************************

; TODO add options page with a "show notifications box"

;   ; returns 0 when we have persmission 
;   (if-not (= 0 (.checkPermission webkit-notifications))
;     (.requestPermission webkit-notifications))))

; ; When a torrent completes, try to inform the user
; (dispatch/react-to #{:completed-torrent} (fn [_ torrent]
;   ; If the user is currently on the site don't show the notification
;   (if-not (.hasFocus js/document)
;     (.createNotification webkit-notifications
;       nil 
;       "notification title"
;       "notification content"
;       ))))

(def online (atom nil))

(on $window :offline (fn [_]
  (reset! online false)))

(on $window :online (fn [_]
  (reset! online true)))

(dispatch/react-to #{:document-ready} (fn [_]
  (reset! online (.-onLine js/navigator))))

(add-watch online nil (fn [_ _ old-val new-val]
  (if (and (false? old-val) new-val)
    (.log js/console "gone online"))
  (if (and old-val (false? new-val))
    (.log js/console "gone offline!"))))

(document-ready (fn [e]
  (.info js/console "document-ready")
  (dispatch/fire :document-ready)))

; TODO: A/B test this to minimise leechers
(on $window :beforeunload (fn [_]
  (if-not (zero? (count (filter active? @torrents)))
    "You still have active torrents")))

(dispatch/react-to #{:document-ready} (fn [_]
  "When provided with a magnet link, use it to initiate a torrent"
  (let [qs (Uri/QueryData. (.-search (.-location js/window)))
        torrent-name (.get qs "dn")
        ; torrent-file (.get gs "xs")
        ]
    ; Some early stage filtering, we need an info hash and a tracker
    ; the tracker may or may not be valid (validation occurs later)
    (if-let [announce-list (js->clj (.getValues qs "tr"))]
      (if-let [info-hash (.get qs "xt")]
        (dispatch/fire #{:add-magnet} 
          {:announce-list announce-list
           :info-hash info-hash}))))))

(.log js/console "js loaded")