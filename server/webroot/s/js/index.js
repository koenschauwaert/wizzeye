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

let colors = ["amber", "aquamarine", "azure", "beige", "black", "blue", "bronze", "brown", "copper", "cyan", "emerald", "gold", "gray", "green", "indigo", "ivory", "magenta", "maroon", "orange", "pink", "purple", "red", "rose", "ruby", "silver", "taupe", "teal", "turquoise", "violet", "white", "yellow"];
let animals = ["cat", "cattle", "dog", "donkey", "goat", "horse", "pig", "rabbit", "aardvark", "aardwolf", "albatross", "alligator", "alpaca", "anaconda", "angelfish", "ant", "anteater", "antelope", "ape", "armadillo", "baboon", "badger", "bandicoot", "barracuda", "basilisk", "bat", "bear", "beaver", "bedbug", "bee", "beetle", "bird", "bison", "blackbird", "boa", "boar", "bobcat", "bonobo", "bug", "butterfly", "buzzard", "camel", "buffalo", "carp", "cat", "catshark", "caterpillar", "catfish", "cattle", "centipede", "chameleon", "cheetah", "chicken", "chimpanzee", "chinchilla", "chipmunk", "clam", "clownfish", "cobra", "cockroach", "cod", "condor", "constrictor", "coral", "cougar", "cow", "coyote", "crab", "crane", "crayfish", "crocodile", "deer", "dog", "dolphin", "donkey", "dormouse", "dragonfly", "dragon", "duck", "eagle", "earthworm", "earwig", "eel", "elephant", "emu", "falcon", "ferret", "firefly", "fish", "flamingo", "flea", "fly", "fox", "frog", "gazelle", "gecko", "giraffe", "goat", "goldfish", "goose", "gorilla", "grasshopper", "grouse", "guppy", "hamster", "hawk", "hedgehog", "hippo", "hookworm", "hornet", "horse", "hoverfly", "hummingbird", "hyena", "impala", "jackal", "jaguar", "jellyfish", "kangaroo", "kingfisher", "kiwi", "koala", "koi", "krill", "ladybug", "leech", "lemming", "leopard", "lion", "lizard", "llama", "lobster", "lungfish", "lynx", "macaw", "mackerel", "mammal", "manatee", "mandrill", "marlin", "marmot", "marsupial", "meerkat", "mite", "mockingbird", "mole", "mongoose", "monkey", "moose", "mosquito", "moth", "mouse", "mule", "nightingale", "octopus", "orangutan", "orca", "ostrich", "otter", "owl", "panda", "panther", "parakeet", "parrot", "peacock", "pelican", "penguin", "pheasant", "pig", "pigeon", "piranha", "platypus", "pony", "prawn", "puma", "python", "rabbit", "raccoon", "rat", "rattlesnake", "reindeer", "reptile", "rhinoceros", "roadrunner", "rooster", "roundworm", "sailfish", "salamander", "salmon", "sawfish", "scorpion", "seahorse", "shark", "sheep", "shrimp", "silkworm", "silverfish", "snail", "snake", "snipe", "sole", "sparrow", "spider", "spoonbill", "squid", "squirrel", "starfish", "stingray", "stoat", "sturgeon", "swan", "swordfish", "tapir", "tarantula", "termite", "tern", "tiger", "toad", "toucan", "trout", "tuna", "turkey", "turtle", "viper", "vole", "walrus", "wasp", "weasel", "whale", "whitefish", "wildcat", "wildebeest", "wolf", "wolverine", "wombat", "woodpecker", "worm", "yak", "zebra", "alpaca", "cat", "chicken", "dog", "donkey", "ferret", "goldfish", "guppy", "horse", "koi", "llama", "sheep", "yak"];

function pick(a) {
  return a[Math.floor(Math.random() * a.length)];
}

$("#room").attr('placeholder', pick(colors) + "-" + pick(animals));

if (document.cookie.split(';').filter(item => item.includes('termsAccepted=1')).length) {
  $("#acceptTerms").prop('checked', true);
}

$("#joinform").submit(function (event) {
  if ($("#acceptTerms").prop('checked')) {
    document.cookie = 'termsAccepted=1;max-age=31536000';
    let room = $("#room").val().toLowerCase() || $("#room").attr('placeholder');
    location.pathname = "/" + room;
  }
  event.preventDefault();
});
