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

let params = new URLSearchParams(document.location.search.substring(1));
let role = params.get('role') || 'observer';

var mediaStream;
var pc;
function create_pc() {
  pc = new RTCPeerConnection({iceServers: [
    {urls: "stun:stun.l.google.com:19302"}
  ]});
  pc.onicecandidate = function (event) {
    if (event.candidate)
      send_data({type: 'ice-candidate', candidate: event.candidate});
  }
  pc.onaddstream = function (event) {
    document.getElementById('remote').srcObject = event.stream;
  }
}

var ws = new WebSocket(location.protocol.replace('http', 'ws') + '//' +
                       location.host + '/ws', 'v1');

function send(msg) {
  console.log("Sending", msg);
  ws.send(JSON.stringify(msg));
}

function send_data(msg) {
  send({type: 'broadcast', data: msg})
}

var join_scheduled = false;

function join_room() {
  join_scheduled = false;
  var room = location.pathname.replace(/^\/*(.*?)\/*$/, '$1');
  if (room != '') {
    send({type: 'join', room: room, role: role});
  }
}

function postJoin() {
  if (!join_scheduled) {
    window.setTimeout(join_room, 1000);
    join_scheduled = true;
  }
}

ws.onopen = function (event) {
  navigator.mediaDevices.getUserMedia({audio: true, video: (role == 'glass-wearer')}).then(function (stream) {
    mediaStream = stream;
    join_room();
  }).catch(function (e) {
    console.error(e);
  });
}

ws.onmessage = function(event) {
  var msg = JSON.parse(event.data);
  console.log("Received", msg);
  switch (msg.type) {
  case 'error':
    console.error("Error " + msg.code + ": " + msg.text);
    postJoin();
    break;
  case 'join':
    if (role != 'glass-wearer' || msg.role != 'observer')
      break;
    create_pc();
    pc.addStream(mediaStream);
    pc.createOffer().then(function (offer) {
      return pc.setLocalDescription(offer);
    }).then(function () {
      send_data(pc.localDescription);
    }).catch(function (e) {
      console.error(e);
    });
    break;
  case 'leave':
    try {
      pc.close();
    } catch (e) {}
    break;
  case 'broadcast':
    msg = msg.data;
    switch (msg.type) {
    case 'offer':
      create_pc();
      pc.setRemoteDescription(new RTCSessionDescription(msg)).then(function () {
        return pc.addStream(mediaStream);
      }).then(function () {
        return pc.createAnswer();
      }).then(function (answer) {
        return pc.setLocalDescription(answer);
      }).then(function () {
        send_data(pc.localDescription);
      }).catch(function (e) {
        console.error(e);
      });
      break;
    case 'answer':
      pc.setRemoteDescription(new RTCSessionDescription(msg));
      break;
    case 'ice-candidate':
      pc.addIceCandidate(new RTCIceCandidate(msg.candidate));
      break;
    default:
      console.error("Unknown data message type " + msg.type);
    }
    break;
  default:
    console.error("Unknown message type " + msg.type);
  };
}
