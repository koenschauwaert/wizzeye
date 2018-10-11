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

package main

import (
	"bufio"
	"bytes"
	"encoding/json"
	"fmt"
)

const SignalingProtocol = "v1.signaling.wizzeye.app"

type MsgType string

const (
	// Indicates an error while processing the last message.
	// Fields: code, text.
	ErrorMsg MsgType = "error"

	// Sent by client to request a pong message.
	PingMsg MsgType = "ping"

	// Response of the server to a client-initiated ping message.
	PongMsg MsgType = "pong"

	// Join a room.
	// Fields: room, role.
	JoinMsg MsgType = "join"

	// Leave current room.
	// Fields: [room], [role].
	LeaveMsg MsgType = "leave"

	// SDP offer to forward to other participant.
	// Fields: payload, [iceServers].
	OfferMsg MsgType = "offer"

	// SDP answer to forward to other participant.
	// Fields: payload.
	AnswerMsg MsgType = "answer"

	// ICE candidate to forward to other participant.
	// Fields: payload.
	IceCandidateMsg MsgType = "ice-candidate"

	// Reset the WebRTC state, as if everyone has just joined.
	ResetMsg MsgType = "reset"
)

type Error struct {
	Code int
	Text string
}

func (e *Error) Error() string {
	return e.Text
}

var (
	ErrUnknown    = &Error{1, "Unknown error"}
	ErrBadMessage = &Error{2, "Bad message"}
	ErrNoRoom     = &Error{3, "This action cannot be performed as no room has been joined yet"}
	ErrRoleTaken  = &Error{4, "Role is already taken in room"}
	ErrBadRoom    = &Error{5, "Invalid room name"}
)

type Role string

const (
	GlassWearerRole Role = "glass-wearer"
	ObserverRole    Role = "observer"
)

// Message is the top structure of JSON messages transfered over the WebSocket
// channel.
type Message struct {
	Origin     *Client         `json:"-"`
	Type       MsgType         `json:"type"`
	Code       int             `json:"code,omitempty"`
	Text       string          `json:"text,omitempty"`
	Room       string          `json:"room,omitempty"`
	Role       Role            `json:"role,omitempty"`
	Payload    json.RawMessage `json:"payload,omitempty"`
	IceServers json.RawMessage `json:"iceServers,omitempty"`
}

func (msg *Message) String() string {
	var buf bytes.Buffer
	w := bufio.NewWriter(&buf)
	fmt.Fprint(w, "{type=", msg.Type)
	if msg.Code != 0 {
		fmt.Fprint(w, ", code=", msg.Code)
	}
	if msg.Text != "" {
		fmt.Fprint(w, ", text=\"", msg.Text, "\"")
	}
	if msg.Room != "" {
		fmt.Fprint(w, ", room=\"", msg.Room, "\"")
	}
	if msg.Role != "" {
		fmt.Fprint(w, ", role=\"", msg.Role, "\"")
	}
	fmt.Fprint(w, "}")
	w.Flush()
	return buf.String()
}

func MakeErrorMsg(err error) *Message {
	msg := &Message{Type: ErrorMsg}
	if e, ok := err.(*Error); ok {
		msg.Code = e.Code
		msg.Text = e.Text
	} else {
		msg.Code = ErrUnknown.Code
		msg.Text = err.Error()
	}
	return msg
}

// vim: set ts=4 sw=4 noet:
