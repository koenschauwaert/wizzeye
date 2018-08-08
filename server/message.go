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

type MsgType string

const (
	// Indicates an error while processing the last message.
	// Fields: code, text.
	ErrorMsg MsgType = "error"

	// Join a room.
	// Fields: room, role.
	JoinMsg MsgType = "join"

	// Leave current room.
	// Fields: [room], [role].
	LeaveMsg MsgType = "leave"

	// Broadcast data to all participants in the current room.
	// Fields: data.
	BroadcastMsg MsgType = "broadcast"
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
)

type Role string

const (
	GlassWearerRole Role = "glass-wearer"
	ObserverRole    Role = "observer"
)

// Message is the top structure of JSON messages transfered over the WebSocket
// channel.
type Message struct {
	Origin *Client         `json:"-"`
	Type   MsgType         `json:"type"`
	Code   int             `json:"code,omitempty"`
	Text   string          `json:"text,omitempty"`
	Room   string          `json:"room,omitempty"`
	Role   Role            `json:"role,omitempty"`
	Data   json.RawMessage `json:"data,omitempty"`
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
	if msg.Data != nil {
		fmt.Fprint(w, ", data=", string(msg.Data[:64]), "...")
	}
	fmt.Fprint(w, "}")
	w.Flush()
	return buf.String()
}

// vim: set ts=4 sw=4 noet:
