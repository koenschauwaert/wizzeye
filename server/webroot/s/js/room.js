/* Copyright (c) 2018 The Wizzeye Authors
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
"use strict";


/*******************************************************************************
 * Parameters
 ******************************************************************************/

let params = new URLSearchParams(document.location.search.substring(1));
let role = params.get('role') || 'observer';
let room = location.pathname.replace(/^\/*(.*?)\/*$/, '$1');


/*******************************************************************************
 * UI helpers
 ******************************************************************************/

$("#roomname").text(room).attr('href', location);
$("#nojs").hide();

let UI = {
  BOXES: ["#statusbox", "#errorbox", "#remote"],

  show: function(box) {
    $.each(this.BOXES, (i, v) => {
      if (v != box)
        $(v).hide();
    });
    $(box).show();
  },

  showStatus: function(msg) {
    $("#status").text(msg);
    this.show("#statusbox");
  },

  showError: function(msg) {
    $("#error").text(msg);
    this.show("#errorbox");
  },

  showVideo: function() {
    this.show("#remote");
  }
};


/*******************************************************************************
 * States
 ******************************************************************************/

let State = {
  ERROR: -1,
  LOADING: 0,
  WEBSOCKET_CONNECT: 1,
  WAITING_FOR_JOIN: 2,
  GET_USER_MEDIA: 3,
  ESTABLISHING: 4,
  CALL_IN_PROGRESS: 5
};

let STATUS_MESSAGES = [
  "Loading", // LOADING
  "Connecting to Wizzeye server", // WEBSOCKET_CONNECT
  "Waiting for remote glass wearer to join", // WAITING_FOR_JOIN
  "Requesting microphone access", // GET_USER_MEDIA
  "Establishing video connection", // ESTABLISHING
  "Call in progress" // CALL_IN_PROGRESS
];

let Err = {
  WEBSOCKET_ERROR: "There was an error when communicating with the Wizzeye server.",
  INVALID_ROOM: "The chosen room name is not valid.",
  ROOM_BUSY: "Another observer has already joined the room.",
  SIGNALING: "An unknown error occurred while communicating with the Wizzeye server.",
  MEDIA_DENIED: "The microphone on this device could not be accessed.",
  WEBRTC: "An error occurred while establishing the video communication."
};

let state = State.LOADING; // global state variable

function setState(newState, error, errorText) {
  if (errorText)
    console.error(errorText);
  if (state == State.ERROR)
    return;
  state = newState;
  switch (state) {
  case State.ERROR:
    console.error(error);
    UI.showError(error);
    break;
  case State.CALL_IN_PROGRESS:
    console.info("Showing video");
    UI.showVideo();
    break;
  default:
    console.info(STATUS_MESSAGES[state]);
    UI.showStatus(STATUS_MESSAGES[state]);
  }
}


/*******************************************************************************
 * UserMedia helpers
 ******************************************************************************/

function requestLocalMedia() {
  if (!('result' in requestLocalMedia)) {
    requestLocalMedia.result = navigator.mediaDevices.getUserMedia({
      audio: true,
      video: (role == 'glass-wearer')
    });
  }
  return requestLocalMedia.result;
}


/*******************************************************************************
 * WebSocket helpers
 ******************************************************************************/

function WizzeyeSocket() {
  this.onerror = null;
  this.onopen = null;
  this.onmessage = null;
  this.socket = new WebSocket(location.protocol.replace('http', 'ws') + '//' +
                              location.host + '/ws', 'v1');
  this.socket.onerror = (event => {
    if (this.onerror != null)
      this.onerror(event);
  });
  this.socket.onopen = (event => {
    if (this.onopen != null)
      this.onopen();
  });
  this.socket.onmessage = (event => {
    let msg = JSON.parse(event.data);
    console.log("Received", msg);
    if (this.onmessage != null)
      this.onmessage(msg);
  });
}

WizzeyeSocket.prototype.send = function(msg) {
  console.log("Sending", msg);
  this.socket.send(JSON.stringify(msg));
}

WizzeyeSocket.prototype.sendData = function(msg) {
  this.send({type: 'broadcast', data: msg})
}


/*******************************************************************************
 * WebRTC helpers
 ******************************************************************************/

let RTC = {
  pc: null,

  closePC: function() {
    if (this.pc != null) {
      this.pc.then(pc => pc.close());
      this._rejectPC("closePC called");
    }
    this.pc = new Promise((resolve, reject) => {
      this._resolvePC = resolve;
      this._rejectPC = reject;
    });
  },

  _createPC: function(ev) {
    let pc = new RTCPeerConnection({iceServers: [
      {urls: "stun:stun.l.google.com:19302"}
    ]});
    pc.onicecandidate = function (event) {
      if (event.candidate)
        ev.onIceCandidate(event.candidate);
    }
    pc.onaddstream = function (event) {
      $("#remote")[0].srcObject = event.stream;
    }
    this._resolvePC(pc);
    return pc;
  },

  makeOffer: function(ev, stream) {
    let pc = this._createPC(ev);
    pc.addStream(stream);
    return pc.createOffer()
      .then(offer => pc.setLocalDescription(offer))
      .then(() => pc.localDescription);
  },

  makeAnswer: function(ev, stream, offer) {
    let pc = this._createPC(ev);
    return pc.setRemoteDescription(offer)
      .then(() => pc.addStream(stream))
      .then(() => pc.createAnswer())
      .then(answer => pc.setLocalDescription(answer))
      .then(() => pc.localDescription);
  },

  setAnswer: function(answer) {
    return this.pc.then(pc => pc.setRemoteDescription(answer));
  },

  addIceCandidate: function(candidate) {
    return this.pc.then(pc => pc.addIceCandidate(candidate));
  }
};

RTC.closePC();


/*******************************************************************************
 * Protocol implementation
 ******************************************************************************/

setState(State.WEBSOCKET_CONNECT);

let ws = new WizzeyeSocket();

ws.onerror = function(e) {
  setState(State.ERROR, Err.WEBSOCKET_ERROR, e);
}

ws.onopen = function() {
  if (state != State.WEBSOCKET_CONNECT)
    return;
  setState(State.WAITING_FOR_JOIN);
  ws.send({type: 'join', room: room, role: role});
}

let rtcEvents = {
  onIceCandidate: function(candidate) {
    ws.sendData({type: 'ice-candidate', candidate: candidate});
  }
};

ws.onmessage = function(msg) {
  switch (msg.type) {
  case 'error':
    RTC.closePC();
    switch (msg.code) {
    case 4:
      setState(State.ERROR, Err.ROOM_BUSY, msg.text);
      break;
    case 5:
      setState(State.ERROR, Err.INVALID_ROOM, msg.text);
      break;
    default:
      setState(State.ERROR, Err.SIGNALING, msg.text);
    }
    break;
  case 'join':
    if (state != State.WAITING_FOR_JOIN)
      break;
    setState(State.GET_USER_MEDIA);
    requestLocalMedia().then(stream => {
      if (state != State.GET_USER_MEDIA)
        return;
      setState(State.ESTABLISHING);
      if (role == 'glass-wearer') {
        RTC.makeOffer(rtcEvents, stream)
          .then(offer => ws.sendData(offer))
          .catch(e => setState(State.ERROR, Err.WEBRTC, e));
      }
    }).catch(e => setState(State.ERROR, Err.MEDIA_DENIED, e));
    break;
  case 'leave':
    if (state > State.WAITING_FOR_JOIN) {
      RTC.closePC();
      setState(State.WAITING_FOR_JOIN);
    }
    break;
  case 'broadcast':
    msg = msg.data;
    switch (msg.type) {
    case 'offer':
      if (state < State.GET_USER_MEDIA)
        break;
      requestLocalMedia()
        .then(stream => RTC.makeAnswer(rtcEvents, stream, msg),
              e => setState(State.ERROR, Err.MEDIA_DENIED, e))
        .then(answer => {
          if (state >= State.GET_USER_MEDIA) {
            ws.sendData(answer);
            setState(State.CALL_IN_PROGRESS);
          }
        }).catch(e => setState(State.ERROR, Err.WEBRTC, e));
      break;
    case 'answer':
      if (state < State.ESTABLISHING)
        break;
      RTC.setAnswer(msg).then(() => {
        if (state >= State.ESTABLISHING)
          setState(State.CALL_IN_PROGRESS);
      }).catch(e => setState(State.ERROR, Err.WEBRTC, e));
      break;
    case 'ice-candidate':
      if (state < State.GET_USER_MEDIA)
        break;
      RTC.addIceCandidate(msg.candidate)
        .catch(e => setState(State.ERROR, Err.WEBRTC, e));
      break;
    default:
      console.warn("Unknown data message type " + msg.type);
    }
    break;
  default:
    console.warn("Unknown message type " + msg.type);
  };
}
