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
	"context"
	"github.com/gorilla/websocket"
	"log"
	"time"
)

type Client struct {
	done      <-chan struct{}
	sendqueue chan<- *Message
	pingqueue chan<- struct{}
}

func HandleClient(ctx context.Context, conn *websocket.Conn) {
	router := RouterFromContext(ctx)
	done := make(chan struct{})
	defer close(done)
	eof := make(chan struct{})
	outgoing := make(chan *Message, 5)
	ping := make(chan struct{}, 1)
	pinged := make(chan struct{}, 1)
	c := &Client{
		done:      done,
		sendqueue: outgoing,
		pingqueue: ping,
	}
	defer c.cleanup(ctx)
	// Timeouts
	pinginterval := time.Duration(Cfg.PingInterval) * time.Second
	pongtimeout := time.Duration(Cfg.PongTimeout) * time.Second
	writetimeout := time.Duration(Cfg.WriteTimeout) * time.Second
	readtimeout := pinginterval + pongtimeout
	// Ping handler
	conn.SetReadDeadline(time.Now().Add(readtimeout))
	conn.SetPongHandler(func(string) error {
		conn.SetReadDeadline(time.Now().Add(readtimeout))
		select {
		case <-pinged:
			router.Alive <- c
		default:
		}
		return nil
	})
	// Reader goroutine
	go func() {
		for {
			msg := new(Message)
			if err := conn.ReadJSON(msg); err != nil {
				if websocket.IsUnexpectedCloseError(err, websocket.CloseNormalClosure, websocket.CloseGoingAway) {
					log.Println("read error:", err)
				}
				close(eof)
				return
			}
			conn.SetReadDeadline(time.Now().Add(readtimeout))
			select {
			case <-pinged:
				router.Alive <- c
			default:
			}
			msg.Origin = c
			LogConn(ctx, "<< ", msg)
			router.Incoming <- msg
		}
	}()
	// Client handler
	ticker := time.NewTicker(pinginterval)
	defer ticker.Stop()
	for {
		select {
		case msg := <-outgoing:
			LogConn(ctx, ">> ", msg)
			conn.SetWriteDeadline(time.Now().Add(writetimeout))
			if err := conn.WriteJSON(msg); err != nil {
				log.Println("write error:", err)
				return
			}
		case <-ping:
			select {
			case pinged <- struct{}{}:
				LogConn(ctx, "Pinging")
				conn.SetReadDeadline(time.Now().Add(pongtimeout)) // short timeout
				conn.SetWriteDeadline(time.Now().Add(writetimeout))
				if err := conn.WriteMessage(websocket.PingMessage, nil); err != nil {
					log.Println("write ping error:", err)
					return
				}
			default:
			}
		case <-ticker.C:
			conn.SetWriteDeadline(time.Now().Add(writetimeout))
			if err := conn.WriteMessage(websocket.PingMessage, nil); err != nil {
				log.Println("write ping error:", err)
				return
			}
		case <-eof:
			return
		}
	}
}

func (c *Client) Send(ctx context.Context, msg *Message) {
	select {
	case c.sendqueue <- msg:
	case <-c.done:
	}
}

func (c *Client) Ping(ctx context.Context) {
	select {
	case c.pingqueue <- struct{}{}:
	case <-c.done:
	}
}

func (c *Client) cleanup(ctx context.Context) {
	RouterFromContext(ctx).Incoming <- &Message{Origin: c, Type: LeaveMsg}
}

// vim: set ts=4 sw=4 noet:
