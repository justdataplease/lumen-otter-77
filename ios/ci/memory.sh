#!/bin/bash
# Prints the runner's memory and free disk every minute, into the step's own log. A run that GitHub stops for want of
# memory leaves no other trace: the job just reads "The operation was canceled".
while true; do
  free -m | awk -v t="$(date -u +%H:%M:%S)" -v d="$(df -h / | awk 'NR==2 {print $4}')" \
    'NR==2 {printf "MEMORY %s used %d MB, available %d MB of %d MB", t, $3, $7, $2} NR==3 {printf ", swap used %d MB, disk free %s\n", $3, d}'
  sleep 60
done
