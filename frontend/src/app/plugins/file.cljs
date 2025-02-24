;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.plugins.file
  (:require
   [app.common.data.macros :as dm]
   [app.common.record :as crc]
   [app.common.uuid :as uuid]
   [app.config :as cf]
   [app.main.data.exports.files :as exports.files]
   [app.main.data.workspace :as dw]
   [app.main.features :as features]
   [app.main.store :as st]
   [app.main.worker :as uw]
   [app.plugins.page :as page]
   [app.plugins.parser :as parser]
   [app.plugins.register :as r]
   [app.plugins.utils :as u]
   [app.util.http :as http]
   [app.util.object :as obj]
   [beicon.v2.core :as rx]))

(deftype FileProxy [$plugin $id]
  Object
  (getPages [_]
    (let [file (u/locate-file $id)]
      (apply array (sequence (map #(page/page-proxy $plugin $id %)) (dm/get-in file [:data :pages])))))

  ;; Plugin data
  (getPluginData
    [self key]
    (cond
      (not (string? key))
      (u/display-not-valid :getPluginData-key key)

      :else
      (let [file (u/proxy->file self)]
        (dm/get-in file [:data :plugin-data (keyword "plugin" (str $plugin)) key]))))

  (setPluginData
    [_ key value]
    (cond
      (or (not (string? key)) (empty? key))
      (u/display-not-valid :setPluginData-key key)

      (and (some? value) (not (string? value)))
      (u/display-not-valid :setPluginData-value value)

      (not (r/check-permission $plugin "content:write"))
      (u/display-not-valid :setPluginData "Plugin doesn't have 'content:write' permission")

      :else
      (st/emit! (dw/set-plugin-data $id :file (keyword "plugin" (str $plugin)) key value))))

  (getPluginDataKeys
    [self]
    (let [file (u/proxy->file self)]
      (apply array (keys (dm/get-in file [:data :plugin-data (keyword "plugin" (str $plugin))])))))

  (getSharedPluginData
    [self namespace key]
    (cond
      (not (string? namespace))
      (u/display-not-valid :getSharedPluginData-namespace namespace)

      (not (string? key))
      (u/display-not-valid :getSharedPluginData-key key)

      :else
      (let [file (u/proxy->file self)]
        (dm/get-in file [:data :plugin-data (keyword "shared" namespace) key]))))

  (setSharedPluginData
    [_ namespace key value]

    (cond
      (or (not (string? namespace)) (empty? namespace))
      (u/display-not-valid :setSharedPluginData-namespace namespace)

      (or (not (string? key)) (empty? key))
      (u/display-not-valid :setSharedPluginData-key key)

      (and (some? value) (not (string? value)))
      (u/display-not-valid :setSharedPluginData-value value)

      (not (r/check-permission $plugin "content:write"))
      (u/display-not-valid :setSharedPluginData "Plugin doesn't have 'content:write' permission")

      :else
      (st/emit! (dw/set-plugin-data $id :file (keyword "shared" namespace) key value))))

  (getSharedPluginDataKeys
    [self namespace]
    (cond
      (not (string? namespace))
      (u/display-not-valid :getSharedPluginDataKeys namespace)

      :else
      (let [file (u/proxy->file self)]
        (apply array (keys (dm/get-in file [:data :plugin-data (keyword "shared" namespace)]))))))

  (createPage
    [_]
    (cond
      (not (r/check-permission $plugin "content:write"))
      (u/display-not-valid :createPage "Plugin doesn't have 'content:write' permission")

      :else
      (let [page-id (uuid/next)]
        (st/emit! (dw/create-page {:page-id page-id :file-id $id}))
        (page/page-proxy $plugin $id page-id))))

  (export
    [self format type]
    (let [type (or (parser/parse-keyword type) :all)]
      (cond
        (not (contains? #{"penpot" "zip"} format))
        (u/display-not-valid :format type)

        (not (contains? (set exports.files/valid-types) type))
        (u/display-not-valid :type type)

        :else
        (let [file       (u/proxy->file self)
              features   (features/get-team-enabled-features @st/state)
              team-id    (:current-team-id @st/state)
              format     (case format
                           "penpot" (if (contains? cf/flags :export-file-v3)
                                      :binfile-v3
                                      :binfile-v1)
                           "zip"    :legacy-zip)]
          (js/Promise.
           (fn [resolve reject]
             (->> (uw/ask-many!
                   {:cmd :export-files
                    :format format
                    :type type
                    :team-id team-id
                    :features features
                    :files [file]})
                  (rx/mapcat
                   (fn [msg]
                     (case (:type msg)
                       :error
                       (rx/throw (ex-info "cannot export file" {:type :export-file}))

                       :progress
                       (rx/empty)

                       :finish
                       (http/send! {:method :get
                                    :uri (:uri msg)
                                    :mode :no-cors
                                    :response-type :buffer}))))
                  (rx/take 1)
                  (rx/map (fn [data] (js/Uint8Array. data)))
                  (rx/subs! resolve reject)))))))))

(crc/define-properties!
  FileProxy
  {:name js/Symbol.toStringTag
   :get (fn [] (str "FileProxy"))})

(defn file-proxy? [p]
  (instance? FileProxy p))

(defn file-proxy
  [plugin-id id]
  (crc/add-properties!
   (FileProxy. plugin-id id)
   {:name "$plugin" :enumerable false :get (constantly plugin-id)}
   {:name "$id" :enumerable false :get (constantly id)}

   {:name "id"
    :get #(dm/str (obj/get % "$id"))}

   {:name "name"
    :get #(-> % u/proxy->file :name)}

   {:name "pages"
    :get #(.getPages ^js %)}))


