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
	"flag"
	"github.com/gorilla/websocket"
	"log"
	"net/http"
	"os"
	"strings"
)

var webroot = flag.String("webroot", "webroot", "web root directory")

var upgrader = websocket.Upgrader{
	CheckOrigin: func(r *http.Request) bool { return true },
}

func LogConn(ctx context.Context, v ...interface{}) {
	v = append([]interface{}{ctx.Value(RemoteAddrKey), ": "}, v...)
	log.Print(v...)
}

func checkSubprotocol(r *http.Request) bool {
	for _, protocol := range websocket.Subprotocols(r) {
		if protocol == SignalingProtocol {
			return true
		}
	}
	return false
}

func websocketHandler(w http.ResponseWriter, r *http.Request) {
	remote := r.Header.Get("X-Real-IP")
	if len(remote) == 0 {
		remote = r.RemoteAddr
	}
	ctx := context.WithValue(r.Context(), RemoteAddrKey, remote)
	if !checkSubprotocol(r) {
		LogConn(ctx, "Subprotocol mismatch")
		http.Error(w, "Subprotocol mismatch", http.StatusBadRequest)
		return
	}
	conn, err := upgrader.Upgrade(w, r, http.Header{
		"Sec-WebSocket-Protocol": []string{SignalingProtocol},
	})
	if err != nil {
		log.Println(err)
		return
	}
	defer conn.Close()
	LogConn(ctx, "New connection")
	HandleClient(ctx, conn)
	LogConn(ctx, "Disconnected")
}

func main() {
	flag.Parse()
	if os.Getenv("JOURNAL_STREAM") != "" {
		// stdout is connected to the systemd journal: remove timestamps
		// from log messages as they are taken care of by the journal
		log.SetFlags(0)
	}
	LoadConfig()
	ctx := ContextWithNewRouter(context.Background())
	http.HandleFunc("/ws", func(rw http.ResponseWriter, req *http.Request) {
		websocketHandler(rw, req.WithContext(ctx))
	})
	fs := http.FileServer(http.Dir(*webroot))
	http.HandleFunc("/", func(rw http.ResponseWriter, req *http.Request) {
		switch {
		case req.URL.Path == "/":
		case strings.HasPrefix(req.URL.Path, "/s/"):
		default:
			req.URL.Path = "/room.html"
		}
		fs.ServeHTTP(rw, req)
	})
	log.Print("Listening on ", Cfg.Listen)
	log.Fatal(http.ListenAndServe(Cfg.Listen, nil))
}

// vim: set ts=4 sw=4 noet:
