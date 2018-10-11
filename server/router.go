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
	Name    string
	Seats   map[Role]*Client
	Waiting map[Role]*Client
}

type Router struct {
	Incoming chan<- *Message
	Alive    chan<- *Client

	queue   chan *Message
	alive   chan *Client
	rooms   map[string]*Room
	clients map[*Client]*Room
}

func ContextWithNewRouter(ctx context.Context) context.Context {
	queue := make(chan *Message, 100)
	alive := make(chan *Client, 100)
	router := &Router{
		Incoming: queue,
		Alive:    alive,
		queue:    queue,
		alive:    alive,
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
		case c := <-r.alive:
			r.handleAlive(ctx, c)
		}
	}
}

func (r *Router) getRoom(name string) *Room {
	room := r.rooms[name]
	if room == nil {
		room = &Room{
			Name:    name,
			Seats:   make(map[Role]*Client),
			Waiting: make(map[Role]*Client),
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
	case PingMsg:
		r.ping(ctx, msg.Origin)
	case JoinMsg:
		r.join(ctx, msg.Origin, msg.Room, msg.Role)
	case LeaveMsg:
		r.leave(ctx, msg.Origin)
	case OfferMsg, AnswerMsg, IceCandidateMsg, ResetMsg:
		r.forward(ctx, msg.Origin, msg)
	default:
		msg.Origin.Send(ctx, MakeErrorMsg(ErrBadMessage))
	}
}

func (r *Router) handleAlive(ctx context.Context, c *Client) {
	room := r.clients[c]
	defer r.putRoom(room)

	if room == nil {
		return
	}

	for role, cc := range room.Seats {
		if cc == c {
			if waiter := room.Waiting[role]; waiter != nil {
				waiter.Send(ctx, MakeErrorMsg(ErrRoleTaken))
				delete(room.Waiting, role)
			}
		}
	}
}

func (r *Router) ping(ctx context.Context, c *Client) {
	c.Send(ctx, &Message{Type: PongMsg})
}

func (r *Router) join(ctx context.Context, c *Client, name string, role Role) {
	switch role {
	case GlassWearerRole, ObserverRole:
	default:
		c.Send(ctx, MakeErrorMsg(ErrBadMessage))
		return
	}
	name = strings.ToLower(name)
	if !roomNameRegexp.MatchString(name) {
		c.Send(ctx, MakeErrorMsg(ErrBadRoom))
		return
	}

	r.leave(ctx, c)

	room := r.getRoom(name)
	defer r.putRoom(room)

	r.clients[c] = room
	if other := room.Seats[role]; other != nil {
		if waiter := room.Waiting[role]; waiter != nil {
			waiter.Send(ctx, MakeErrorMsg(ErrRoleTaken))
		}
		room.Waiting[role] = c
		other.Ping(ctx)
		return
	}
	room.Seats[role] = c
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

	for wr, wc := range room.Waiting {
		if wc == c {
			delete(room.Waiting, wr)
		}
	}

	var role Role
	for sr, sc := range room.Seats {
		if sc == c {
			delete(room.Seats, sr)
			role = sr
		}
	}
	if role != "" {
		for _, sc := range room.Seats {
			sc.Send(ctx, &Message{Type: LeaveMsg, Room: room.Name, Role: role})
		}
		if waiter := room.Waiting[role]; waiter != nil {
			r.join(ctx, waiter, room.Name, role)
		}
	}

	delete(r.clients, c)
}

func (r *Router) forward(ctx context.Context, c *Client, msg *Message) {
	room := r.clients[c]
	defer r.putRoom(room)

	if room != nil {
		for _, cc := range room.Seats {
			if cc != c {
				cc.Send(ctx, msg)
			}
		}
	} else {
		c.Send(ctx, MakeErrorMsg(ErrNoRoom))
	}
}

// vim: set ts=4 sw=4 noet:
