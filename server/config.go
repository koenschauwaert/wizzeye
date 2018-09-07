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
	"flag"
	"github.com/BurntSushi/toml"
	"log"
)

type ContextKey int

const (
	RouterKey ContextKey = iota
	RemoteAddrKey
)

// Config is the top structure of the JSON configuration file.
type Config struct {
	Listen       string
	PingInterval int
	PongTimeout  int
	WriteTimeout int
}

// Global configuration variable
var Cfg Config

var configFile = flag.String("config", "config.toml", "configuration file")

func LoadConfig() {
	// Default values
	Cfg.Listen = ":8080"
	Cfg.PingInterval = 60 // seconds
	Cfg.PongTimeout = 5   // seconds
	Cfg.WriteTimeout = 10 // seconds
	// Load configuration file
	if _, err := toml.DecodeFile(*configFile, &Cfg); err != nil {
		log.Print(*configFile, ": ", err)
	}
}

// vim: set ts=4 sw=4 noet:
