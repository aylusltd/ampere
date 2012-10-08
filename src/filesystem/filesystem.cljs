(ns filesystem.filesystem
	(:use-macros [async.macros :only [let-async async]]))

(defn request-file-system [type size]
		(async [success-callback error-callback]
			(.webkitRequestFileSystem js/window (aget js/window (name type)) size success-callback error-callback)
	))

(defn request-quota [type size]
		(async [success-callback error-callback]
			(.requestQuota (.-webkitStorageInfo js/window) (aget js/window (name type)) size success-callback error-callback)
	))

(defn request-quota-then-filesystem [type size]
	"A combination of the above two functions, if request-quota succeeds,
	then call request-file-system"
	(let-async [granted-bytes (request-quota type size)]
		(request-file-system type granted-bytes)))