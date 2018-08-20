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
	"regexp"
	"strings"
)

var roomNameRegexp = regexp.MustCompile(`^[-_a-z0-9]{5,64}$`)

type Room struct {
	Name  string
	Seats map[Role]*Client
}

type Router struct {
	Incoming chan<- *Message

	queue   chan *Message
	rooms   map[string]*Room
	clients map[*Client]*Room
}

func ContextWithNewRouter(ctx context.Context) context.Context {
	queue := make(chan *Message, 100)
	router := &Router{
		Incoming: queue,
		queue:    queue,
		rooms:    make(map[string]*Room),
		clients:  make(map[*Client]*Room),
	}
	ctx = context.WithValue(ctx, RouterKey, router)
	go router.run(ctx)
	return ctx
}

func RouterFromContext(ctx context.Context) *Router {
	return ctx.Value(RouterKey).(*Router)
}

func (r *Router) run(ctx context.Context) {
	for {
		select {
		case msg := <-r.queue:
			r.handle(ctx, msg)
		case <-ctx.Done():
			return
		}
	}
}

func (r *Router) getRoom(name string) *Room {
	room := r.rooms[name]
	if room == nil {
		room = &Room{
			Name:  name,
			Seats: make(map[Role]*Client),
		}
		r.rooms[name] = room
	}
	return room
}

func (r *Router) putRoom(room *Room) {
	if room != nil && len(room.Seats) == 0 {
		delete(r.rooms, room.Name)
	}
}

func (r *Router) handle(ctx context.Context, msg *Message) {
	switch msg.Type {
	case JoinMsg:
		r.join(ctx, msg.Origin, msg.Room, msg.Role)
	case LeaveMsg:
		r.leave(ctx, msg.Origin)
	case BroadcastMsg:
		r.broadcast(ctx, msg.Origin, msg)
	default:
		msg.Origin.SendError(ctx, ErrBadMessage)
	}
}

func (r *Router) join(ctx context.Context, c *Client, name string, role Role) {
	switch role {
	case GlassWearerRole, ObserverRole:
	default:
		c.SendError(ctx, ErrBadMessage)
		return
	}
	name = strings.ToLower(name)
	if !roomNameRegexp.MatchString(name) {
		c.SendError(ctx, ErrBadRoom)
		return
	}

	r.leave(ctx, c)

	room := r.getRoom(name)
	defer r.putRoom(room)

	if room.Seats[role] != nil {
		c.SendError(ctx, ErrRoleTaken)
		return
	}
	room.Seats[role] = c
	r.clients[c] = room
	for sr, sc := range room.Seats {
		if sc != c {
			sc.Send(ctx, &Message{
				Type: JoinMsg,
				Room: room.Name,
				Role: role,
			})
			c.Send(ctx, &Message{
				Type: JoinMsg,
				Room: room.Name,
				Role: sr,
			})
		}
	}
}

func (r *Router) leave(ctx context.Context, c *Client) {
	room := r.clients[c]
	defer r.putRoom(room)

	if room == nil {
		return
	}

	var role Role
	for sr, sc := range room.Seats {
		if sc == c {
			delete(room.Seats, sr)
			role = sr
		}
	}
	for _, sc := range room.Seats {
		sc.Send(ctx, &Message{Type: LeaveMsg, Room: room.Name, Role: role})
	}

	delete(r.clients, c)
}

func (r *Router) broadcast(ctx context.Context, c *Client, msg *Message) {
	room := r.clients[c]
	defer r.putRoom(room)

	if room != nil {
		for _, cc := range room.Seats {
			if cc != c {
				cc.Send(ctx, msg)
			}
		}
	} else {
		c.SendError(ctx, ErrNoRoom)
	}
}

// vim: set ts=4 sw=4 noet:
