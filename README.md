# nuka

nuka is a Clojure library that loosely occupies the space of
devops. It includes:

* A function-based DSL (no macros) for generating shell
  commands/scripts.
* Facilities for starting external processes and communicating with
  their stdin/stdout/stderr via core.async channels.
* Facilities for starting processes on remote machines (via SSH) and
  communicating with their stdin/stdout/stderr via core.async
  channels.
* A multi-threaded planner that allows you to define plans that
  contain tasks and can depend on each other and are executed in the
  correct order. A bit like make, but instead of a Makefile you define
  it via a Clojure data structure. Includes visualisation.

## Generating shell commands

The `nuka.script` contains functions that can generate shell
commands. You can use this functionality to produce bash scripts, or
you can execute the generated code directly (as explained in the next
section).

``` clojure
(require '[nuka.script :as script :refer [call]])

(call :systemctl "stop" "myservice" :user)
```

The `call` function has no side-effects, it just produces a data
structure which represents a call to an external process without
actually calling it. This data structure can be rendered to bash
syntax:

``` clojure
(require '[nuka.script.bash :as bash])

(bash/render (call :systemctl "stop" "myservice" :user))
```

Gives you:

``` clojure
"systemctl 'stop' 'myservice' --user"
```

Notice that the `"stop"` string is left as-is, while the `:user`
keyword is rendered as a long parameter. nuka always converts keywords
with more than one character into long parameters (with a double
dash), and single-character keywords into short parameters (with a
single dash):

``` clojure
> (bash/render (call :ls :l))
"ls -l"
```

The `nuka.script` namespace contains more functions to combine calls
together:

``` clojure
> (bash/render
   (script/chain-and
    (call :cd "myproject")
    (call :clojure "-A:uberjar")))
"cd 'myproject' && clojure '-A:uberjar'"
```

Some notable ones are `chain-and`, `chain-or` and `pipe`. There are
also functions for for-loops, bindings, if, assignment etc. `qq`
forces double-quoting a string, and `raw` allows you to pass a string
that will be rendered as raw code and will not be processed in any
way.

If you're writing a sequence of commands out to a file, use the
`script` function:

``` clojure
> (println
   (bash/render
    (script/script
     (call :ls :h)
     (call :ping "127.0.0.1"))))

ls -h
ping '127.0.0.01'
```

## External processes

Instead of rendering into bash syntax, you can invoke the external
processes directly:

``` clojure
(require '[nuka.script :as script :refer [call]]
         '[nuka.exec :as exec])

(-> (call :ls :l) exec/exec exec/>print)
```

Prints:

```
CMD: ["/bin/sh" "-c" "ls -l"]
total 80
-rw-r--r--  1 sideris  staff    425 18 Feb 18:24 deps.edn
-rw-r--r--  1 sideris  staff    504 24 Jul  2019 readme.md
drwxr-xr-x  3 sideris  staff     96 25 Jul  2019 src
END: ["/bin/sh" "-c" "ls -l"]
```

So the `exec` function results in the execution of the `ls` command,
and the `>print` function consumes and prints the standard out and
standard error of `ls` (each with different colours).

The lines of the output can also be collected into a vector of strings
using `>slurp`:

``` clojure
> (-> (call :ls :l) exec/exec exec/>slurp)
["total 80"
 "-rw-r--r--  1 sideris  staff    425 18 Feb 18:24 deps.edn"
 "-rw-r--r--  1 sideris  staff    504 24 Jul  2019 readme.md"
 "drwxr-xr-x  3 sideris  staff     96 25 Jul  2019 src"]
```

`exec` is asynchronous, this returns immediately:

``` clojure
(-> (call :sleep 3) exec/exec)
```

If you'd rather wait, do:

``` clojure
(-> (call :sleep 3) exec/exec exec/wait)
```

...which will pause for 3 seconds before returning.

You can also pass an option to wait so that it throws an exception on
a non-zero exit code:

``` clojure
> (-> (call :fake) exec/exec (exec/wait {:zero-throw true}))
Execution error (ExceptionInfo) at nuka.exec/wait (exec.clj:130).
Process failed!
```

The exit code can be inspected directly:

``` clojure
(-> (call :fake) exec/exec exec/exit-code)
127
```

You can also kill a long-running process:

``` clojure
(def p (-> (call :sleep 1000) exec/exec))

;; this blocks, so you should interrupt it:

(exec/wait p)

;; kill the long-running process:

(exec/kill p)

;; this no longer blocks:

(exec/wait p)
```

## Long-running processes

Generally, the `exec` function gives you an instance of the
`nuka.exec.SystemProcess` record which acts as a handle to the
external process. Among other things, it contains 3 core.async
channels that correspond to standard in/out/error. This provides some
interesting opportunities for sophisticated interactions with the
external process.

For example, let's look at how we could keep around a running instance
of the `bc` command to do some math for us. Just as a reminder, you
can use `bc` from the command line like so:

``` shell
» echo '1+2' | bc
3
```

So from a Clojure REPL you can:

``` clojure
(require '[clojure.core.async :refer [<!! >!!] :as async])

;; start the process
(def bc (-> (call :bc) exec/exec))

;; attach a print consumer to it
(exec/>print bc)

;; send it some input using core.async blocking put
(>!! (:in bc) "1+2")  ;; prints 3

(>!! (:in bc) "43-1") ;; prints 42

;; we're done with the math, clean up the process
(exec/kill bc)
```

It's important to note that in the above example there is only one
`bc` process that sticks around waiting for our input.

## Remote processes

TODO

## Planner

TODO

## License

Copyright © 2015-2020 Efstathios Sideris

Distributed under the Eclipse Public License either version 1.0 or (at
your option) any later version.
