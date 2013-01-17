//     DataChannel polyfill Web browser polyfill that implements the WebRTC 
//     DataChannel API over a websocket. It implements the full latest DataChannel 
//     API specification defined at 2012-10-21.
//     Copyright (C) 2013  Jesús Leganés Combarro

//     This program is free software: you can redistribute it and/or modify
//     it under the terms of the GNU Affero General Public License as
//     published by the Free Software Foundation, either version 3 of the
//     License, or (at your option) any later version.

//     This program is distributed in the hope that it will be useful,
//     but WITHOUT ANY WARRANTY; without even the implied warranty of
//     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
//     GNU Affero General Public License for more details.

//     You should have received a copy of the GNU Affero General Public License
//     along with this program.  If not, see <http://www.gnu.org/licenses/>.

function DCPF_install(ws_url)
{
  // Fallbacks for vendor-specific variables until the spec is finalized.
  var RTCPeerConnection = window.RTCPeerConnection || window.webkitRTCPeerConnection || window.mozRTCPeerConnection;

  // Check if browser has support for WebRTC PeerConnection
  if(RTCPeerConnection == undefined)
  {
    console.error("Your browser doesn't support RTCPeerConnection, please use "+
                  "one of the latest versions of Chrome/Chromium or Firefox");
    return "old browser";
  }

//Holds the STUN server to use for PeerConnections.
  var SERVER = "stun:stun.l.google.com:19302";

  // Check if browser has support for native WebRTC DataChannel
  function checkSupport()
  {
      var pc = new RTCPeerConnection({"iceServers": [{"url": SERVER}]})

      try{pc.createDataChannel()}
      catch(e)
      {
          return false
      }

      return true
  }

  if(checkSupport())
  {
    console.log("Using native WebRTC DataChannel");
    return "native";
  }

  console.warn("Adding WebRTC DataChannel polyfill...");

  // DataChannel polyfill using WebSockets as 'underlying data transport'
  function RTCDataChannel()
  {
    var self = this

    // Use a WebSocket as 'underlying data transport' to create the DataChannel
    this._udt = new WebSocket(ws_url)
    this._udt.binaryType = "arraybuffer"
    this._udt.onerror = function(error)
    {
        if(self.onerror)
            self.onerror(error)
    }

    this.close = function(){this._udt.close()}
    // BUGFIX: undefined onerror causes issues in some chrome versions
    this.send  = function(data, onerror){
      this._udt.send(data);
    }

    this.readyState = "connecting"
  }

  // Create a signalling channel with a WebSocket on the proxy server with the
  // defined ID and wait for new 'create' messages to create new DataChannels
  function setId(pc, id)
  {
    var socket = new WebSocket(ws_url)
        socket.binaryType = "arraybuffer"
        socket.onopen = function()
        {
            socket.onmessage = function(message)
            {
                var args = JSON.parse(message.data)

                if(args[0] == 'create')
                    ondatachannel(pc, args[1], args[2])
            }

            socket.send(JSON.stringify(['setId', "pc."+id]))
        }
        socket.onerror = function(error)
        {
            console.error(error)
        }
  }

  // Set the PeerConnection peer ID
  function setPeerId(pc, peerId)
  {
    pc._peerId = "pc."+peerId
  }

  // Private DataChannel factory function
  function createDataChannel(pc, configuration)
  {
    var channel = new RTCDataChannel()
        channel.label = configuration.label
        channel.reliable = true

    if(configuration.reliable != undefined)
      channel.reliable = configuration.reliable

    channel._udt.onclose = function()
    {
      if(pc.readyState == "closed")
        return;

      if(channel.readyState == "closing"
      || channel.readyState == "closed")
        return;

      channel.readyState = "closed"

      if(channel.onclose)
        channel.onclose()
    }

    return channel
  }

  // Public function to initiate the creation of a new DataChannel
  RTCPeerConnection.prototype.createDataChannel = function(label, dataChannelDict)
  {
    if(!this._peerId)
    {
      console.warn("peerId is not defined")
      return
    }

    if(this.readyState == "closed")
      throw INVALID_STATE_ERR;

    label = label || ""
    dataChannelDict = dataChannelDict || {}

    var configuration = {label: label}
    if(dataChannelDict.reliable != undefined)
        configuration['reliable'] = dataChannelDict.reliable;

    var self = this

    var channel = createDataChannel(this, configuration)
    console.log('your channel is created', channel)
        channel._udt.onopen = function()
        {

          console.log('your channel is open')
          // Wait until the other end of the channel is ready
          channel._udt.onmessage = function(message)
          {
            if(message.data == 'ready')
            {
              // PeerConnection is closed, do nothing
              if(self.readyState == "closed")
                return;

              // Set onmessage event to bypass messages to user defined function
              channel._udt.onmessage = function(message)
              {
                console.log('udt', message, channel)
                if(channel.onmessage)
                  channel.onmessage(message)
              }

              // Set channel as open
              channel.readyState = "open"

              if(channel.onopen)
                channel.onopen()
            }
          }

          // Query to the other peer to create a new DataChannel with us
          channel.send(JSON.stringify(["create", self._peerId, configuration]))
        }

    return channel
  }

  // Private function to 'catch' the 'ondatachannel' event
  function ondatachannel(pc, socketId, configuration)
  {
    if(pc.readyState == "closed")
      return;

    var channel = createDataChannel(pc, configuration)
        channel._udt.onopen = function()
        {
            // Set onmessage event to bypass messages to user defined function
            channel._udt.onmessage = function(message)
            {
              console.log('udt', message, channel)
              if(channel.onmessage)
                channel.onmessage(message)
            }

            // Set channel as open
            channel.readyState = "open"

            channel.send(JSON.stringify(["ready", socketId]))

            var evt = document.createEvent('Event')
                evt.initEvent('datachannel', true, true)
                evt.channel = channel
            if(pc.ondatachannel)
                pc.ondatachannel(evt);
        }
  }

  // Get the SDP session ID from a RTCSessionDescription object
  function getId(description)
  {
    var pattern = /^o=.+/gm
    var result = pattern.exec(description.sdp);

    return result[0].substring(2)
  }

  // Overwrite setters to catch the session IDs
  var setLocalDescription  = RTCPeerConnection.prototype.setLocalDescription
  var setRemoteDescription = RTCPeerConnection.prototype.setRemoteDescription

  RTCPeerConnection.prototype.setLocalDescription = function(description, successCallback, failureCallback)
  {
    setId(this, getId(description))

    setLocalDescription.call(this, description, successCallback, failureCallback)
  }

  RTCPeerConnection.prototype.setRemoteDescription = function(description, successCallback, failureCallback)
  {
    setPeerId(this, getId(description))

    setRemoteDescription.call(this, description, successCallback, failureCallback)
  }

  return "polyfill";
}
