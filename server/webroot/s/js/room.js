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
let pingInterval = params.has('pingInterval') ? parseInt(params.get('pingInterval')) : 30;
let room = location.pathname.replace(/^\/*(.*?)\/*$/, '$1');


/*******************************************************************************
 * UI helpers
 ******************************************************************************/

document.title = room + " • " + document.title;
$("#roomname").text(room).attr('href', location);

let UI = {
  BOXES: ["#statusbox", "#errorbox", "#remote"],

  show: function(box) {
    $.each(this.BOXES, (i, v) => {
      if (v != box)
        $(v).addClass('d-none');
    });
    $(box).removeClass('d-none');
  },

  showStatus: function(msg, hint) {
    $("#status").text(msg);
    $("#status-hint").text(hint);
    this.show("#statusbox");
  },

  showError: function(msg) {
    $("#error").text(msg);
    this.show("#errorbox");
  },

  showVideo: function() {
    this.show("#remote");
  },

  showTurbulence: function() {
    $("#notice-turbulence").removeClass('d-none');
  },

  hideTurbulence: function() {
    $("#notice-turbulence").addClass('d-none');
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

let STATUS_HINTS = [
  "", // LOADING
  "", // WEBSOCKET_CONNECT
  "Please direct the remote glass wearer to this room.  You can send the URL of this page to open on an Android phone with the Wizzeye app installed.", // WAITING_FOR_JOIN
  "Please allow this website to access your microphone.", // GET_USER_MEDIA
  "", // ESTABLISHING
  "" // CALL_IN_PROGRESS
];

let Err = {
  WEBSOCKET_ERROR: "The Wizzeye server is momentarily unreachable.  Try reloading this page.",
  INVALID_ROOM: "The chosen room name is not valid.  Try choosing another name.",
  ROOM_BUSY: "Another observer has already joined the room.  You cannot join this room until she has left.",
  SIGNALING: "An error has occurred while communicating with the Wizzeye server.  Try reloading this page.",
  MEDIA_DENIED: "The microphone on this device could not be accessed.  Check that you have a microphone and that your browser settings allow this website to use it.",
  WEBRTC: "An error occurred while establishing the video communication.  Try reloading this page.",
  ICE: "We could not reach the glass wearer's device.  This could be due to restrictive firewalls.  Consider adding a TURN server in the settings of the mobile app."
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
    UI.hideTurbulence();
    break;
  case State.CALL_IN_PROGRESS:
    console.info("Showing video");
    UI.showVideo();
    break;
  default:
    console.info(STATUS_MESSAGES[state]);
    UI.showStatus(STATUS_MESSAGES[state], STATUS_HINTS[state]);
    UI.hideTurbulence();
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
  this.onclose = null;
  this.onmessage = null;
  this.socket = new WebSocket(location.protocol.replace('http', 'ws') + '//' +
                              location.host + '/ws',
                              'v1.signaling.wizzeye.app');
  this.socket.onerror = (event => {
    if (this.onerror != null)
      this.onerror(event);
  });
  this.socket.onopen = (event => {
    if (this.onopen != null)
      this.onopen();
  });
  this.socket.onclose = (event => {
    if (this.onclose != null)
      this.onclose();
  });
  this.socket.onmessage = (event => {
    let msg = JSON.parse(event.data);
    console.log("Received", msg);
    if (msg.type == 'pong')
      this.pongReceived = true;
    else if (this.onmessage != null)
      this.onmessage(msg);
  });
  if (pingInterval > 0) {
    this.pongReceived = true;
    this.intervalId = window.setInterval(() => {
      if (this.pongReceived) {
        this.pongReceived = false;
        this.send({type: 'ping'});
      } else if (this.onerror != null) {
        this.onerror("Ping timeout");
      }
    }, pingInterval * 1000);
  }
}

WizzeyeSocket.prototype.close = function() {
  if (pingInterval > 0)
    window.clearInterval(this.intervalId);
  this.socket.close();
}

WizzeyeSocket.prototype.send = function(msg) {
  console.log("Sending", msg);
  this.socket.send(JSON.stringify(msg));
}


/*******************************************************************************
 * WebRTC helpers
 ******************************************************************************/

let RTC = {
  onIceConnected: null,
  onIceDisconnected: null,
  onIceFailed: null,
  onIceCandidate: null,
  pc: null,

  closePC: function() {
    if (this.pc != null) {
      this.pc.then(pc => pc.close()).catch(e => { /* ignore */ });
      this._rejectPC("closePC called");
    }
    this.pc = new Promise((resolve, reject) => {
      this._resolvePC = resolve;
      this._rejectPC = reject;
    });
  },

  _createPC: function(iceServers) {
    if (iceServers === undefined)
      iceServers = [{urls: "stun:stun.l.google.com:19302"}];
    let pc = new RTCPeerConnection({iceServers: iceServers});
    pc.oniceconnectionstatechange = (event => {
      console.log("ICE connection state: " + pc.iceConnectionState);
      switch(pc.iceConnectionState) {
      case "connected":
        if (this.onIceConnected != null)
          this.onIceConnected();
        break;
      case "disconnected":
        if (this.onIceDisconnected != null)
          this.onIceDisconnected();
        break;
      case "failed":
        if (this.onIceFailed != null)
          this.onIceFailed();
        break;
      }
    });
    pc.onicecandidate = (event => {
      if (event.candidate && this.onIceCandidate != null)
        this.onIceCandidate(event.candidate);
    });
    pc.onaddstream = (event => {
      $("#remote")[0].srcObject = event.stream;
    });
    this._resolvePC(pc);
    return pc;
  },

  makeOffer: function(stream) {
    let pc = this._createPC();
    pc.addStream(stream);
    return pc.createOffer()
      .then(offer => pc.setLocalDescription(offer))
      .then(() => pc.localDescription);
  },

  makeAnswer: function(stream, offer, iceServers) {
    let pc = this._createPC(iceServers);
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

function die(error, message) {
  RTC.closePC();
  ws.send({type: 'leave'});
  ws.close();
  setState(State.ERROR, error, message);
}

ws.onerror = function(e) {
  die(Err.WEBSOCKET_ERROR, e);
}

ws.onopen = function() {
  if (state != State.WEBSOCKET_CONNECT)
    return;
  setState(State.WAITING_FOR_JOIN);
  ws.send({type: 'join', room: room, role: role});
}

ws.onclose = function() {
  die(Err.WEBSOCKET_ERROR, "Websocket closed");
}

RTC.onIceConnected = function() {
  UI.hideTurbulence();
  if (state == State.ESTABLISHING)
    setState(State.CALL_IN_PROGRESS);
}

RTC.onIceDisconnected = function() {
  UI.showTurbulence();
}

RTC.onIceFailed = function() {
  RTC.closePC();
  switch (state) {
  case State.ESTABLISHING:
    die(Err.ICE);
    break;
  case State.CALL_IN_PROGRESS:
    setState(State.ESTABLISHING);
    ws.send({type: 'reset'});
    break;
  }
}

RTC.onIceCandidate = function(candidate) {
  ws.send({type: 'ice-candidate', payload: candidate});
}

function establish() {
  requestLocalMedia().then(stream => {
    if (state != State.GET_USER_MEDIA)
      return;
    setState(State.ESTABLISHING);
    if (role == 'glass-wearer') {
      RTC.makeOffer(stream)
        .then(offer => ws.send({type: 'offer', payload: offer}))
        .catch(e => die(Err.WEBRTC, e));
    }
  }).catch(e => die(Err.MEDIA_DENIED, e));
}

ws.onmessage = function(msg) {
  switch (msg.type) {
  case 'error':
    switch (msg.code) {
    case 4:
      die(Err.ROOM_BUSY, msg.text);
      break;
    case 5:
      die(Err.INVALID_ROOM, msg.text);
      break;
    default:
      die(Err.SIGNALING, msg.text);
    }
    break;
  case 'join':
    if (state != State.WAITING_FOR_JOIN)
      break;
    setState(State.GET_USER_MEDIA);
    establish();
    break;
  case 'leave':
    if (state > State.WAITING_FOR_JOIN) {
      RTC.closePC();
      setState(State.WAITING_FOR_JOIN);
    }
    break;
  case 'reset':
    if (state >= State.ESTABLISHING) {
      RTC.closePC();
      setState(State.ESTABLISHING);
      establish();
    }
    break;
  case 'offer':
    if (state < State.GET_USER_MEDIA)
      break;
    RTC.closePC();
    if (state > State.ESTABLISHING)
      setState(State.ESTABLISHING);
    requestLocalMedia()
      .then(stream => {
              if (state >= State.GET_USER_MEDIA)
                setState(State.ESTABLISHING);
              return RTC.makeAnswer(stream, msg.payload, msg.iceServers)
            }, e => die(Err.MEDIA_DENIED, e))
      .then(answer => {
        if (state >= State.ESTABLISHING) {
          ws.send({type: 'answer', payload: answer});
        }
      }).catch(e => die(Err.WEBRTC, e));
    break;
  case 'answer':
    if (state != State.ESTABLISHING)
      break;
    RTC.setAnswer(msg.payload).catch(e => die(Err.WEBRTC, e));
    break;
  case 'ice-candidate':
    if (state < State.GET_USER_MEDIA)
      break;
    RTC.addIceCandidate(msg.payload)
      .catch(e => die(Err.WEBRTC, e));
    break;
  default:
    console.warn("Unknown message type " + msg.type);
  };
}
