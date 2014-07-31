#!/usr/bin/env python
#
# Runs the command defined in the command line, and kills the child
# process when a SIGTERM is received.
#
# This is needed because of two issues:
# - Java's Process.destroy() will send a SIGTERM to the child process
# - sbt is a shell script that runs Java as a child (instead of calling
#   exec), and bash will not pass signals to its children (or at least
#   doesn't in this case).
#
# So this allows us to actually kill sbt.
#
import errno
import os
import shlex
import signal
import sys

child = None
args = [ sys.argv[1], '-Dsbt.log.noformat=true' ]
if len(sys.argv) > 2:
  args += shlex.split(sys.argv[2])

def kill_child(signum, frame):
  if child:
    os.killpg(child, signal.SIGINT)

signal.signal(signal.SIGTERM, kill_child)
child = os.fork()
if child == 0:
  os.setsid()
  os.execvp(args[0], args)

while True:
  try:
    os.waitpid(child, 0)
    break
  except OSError, e:
    if e.errno != errno.EINTR:
      raise

