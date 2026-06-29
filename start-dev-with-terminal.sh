#!/usr/bin/env bash
#
# start-dev-with-terminal.sh
#
# Starts the basis-consumer-health Quarkus app and a standalone SICCT eHealth-KT
# side by side: left pane = basis-consumer output, right pane = SICCT terminal.
#
# Split strategy (auto-detected):
#   1. tmux        — one window, two panes (left = consumer, right = terminal)
#   2. gnome-terminal + xdotool — two windows tiled left/right half of the screen
#
# Override the commands or terminal args with environment variables:
#   LEFT_CMD        command for the left  pane  (default: quarkus:dev for quarkus-server)
#   RIGHT_CMD       command for the right pane  (default: java -jar ehealth-kt.jar)
#   EHEALTH_KT_ARGS extra args passed to the SICCT terminal (e.g. "--ui JAVAFX --no-konnektor-trust")
#
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$ROOT"

KT_JAR="$ROOT/sicct-ehealth-kt/app/target/ehealth-kt.jar"
EHEALTH_KT_ARGS="${EHEALTH_KT_ARGS:-}"

# --- Left pane: the basis-consumer-health Quarkus application (dev mode, hot reload).
LEFT_CMD="${LEFT_CMD:-./mvnw -pl quarkus-server quarkus:dev}"

# --- Right pane: the standalone SICCT eHealth-Kartenterminal.
#     Build the shaded jar on demand if it is missing.
if [[ ! -f "$KT_JAR" ]]; then
  echo ">> Building SICCT terminal jar (one-time): $KT_JAR"
  ./mvnw -q -pl sicct-ehealth-kt/app -am install -DskipTests
fi
RIGHT_CMD="${RIGHT_CMD:-java -jar \"$KT_JAR\" $EHEALTH_KT_ARGS}"

echo ">> Repo:        $ROOT"
echo ">> Left  (consumer): $LEFT_CMD"
echo ">> Right (terminal): $RIGHT_CMD"

# ---------------------------------------------------------------------------
# 1) tmux — preferred: a single window split into two panes.
# ---------------------------------------------------------------------------
if command -v tmux >/dev/null 2>&1; then
  SESSION="basis-dev"
  echo ">> Using tmux session '$SESSION' (Ctrl-b o to switch panes, Ctrl-b & to kill)."
  tmux kill-session -t "$SESSION" 2>/dev/null || true
  tmux new-session  -d -s "$SESSION" -c "$ROOT"
  tmux send-keys    -t "$SESSION" "$LEFT_CMD" C-m
  tmux split-window -h -t "$SESSION" -c "$ROOT"
  tmux send-keys    -t "$SESSION" "$RIGHT_CMD" C-m
  tmux select-pane  -t "$SESSION".0
  tmux set-option   -t "$SESSION" mouse on
  exec tmux attach-session -t "$SESSION"
fi

# ---------------------------------------------------------------------------
# 2) gnome-terminal + xdotool — two windows tiled to the left/right halves.
# ---------------------------------------------------------------------------
if command -v gnome-terminal >/dev/null 2>&1 && command -v xdotool >/dev/null 2>&1 \
   && [[ -n "${DISPLAY:-}" ]]; then
  echo ">> tmux not found; using two gnome-terminal windows tiled left/right."

  L_TITLE="basis-consumer-health"
  R_TITLE="sicct-ehealth-kt"

  # 'exec bash' keeps the window open after the process exits so output stays visible.
  gnome-terminal --title="$L_TITLE" -- bash -lc "cd '$ROOT'; $LEFT_CMD;  exec bash"
  gnome-terminal --title="$R_TITLE" -- bash -lc "cd '$ROOT'; $RIGHT_CMD; exec bash"

  # Wait for both windows to map, then tile them.
  read -r SW SH < <(xdotool getdisplaygeometry)
  HALF=$(( SW / 2 ))

  wait_win() {  # $1 = title
    for _ in $(seq 1 50); do
      local id; id="$(xdotool search --name "^$1$" 2>/dev/null | tail -1 || true)"
      [[ -n "$id" ]] && { echo "$id"; return 0; }
      sleep 0.2
    done
    return 1
  }

  LID="$(wait_win "$L_TITLE")" || { echo "!! left window not found"; exit 1; }
  RID="$(wait_win "$R_TITLE")" || { echo "!! right window not found"; exit 1; }

  # Undo any maximize so explicit move/size sticks, then tile.
  for id in "$LID" "$RID"; do
    xdotool windowstate --remove MAXIMIZED_VERT --remove MAXIMIZED_HORZ "$id" 2>/dev/null || true
  done
  xdotool windowsize "$LID" "$HALF" "$SH"; xdotool windowmove "$LID" 0       0
  xdotool windowsize "$RID" "$HALF" "$SH"; xdotool windowmove "$RID" "$HALF" 0
  xdotool windowactivate "$LID"

  echo ">> Launched. Left=$L_TITLE  Right=$R_TITLE"
  exit 0
fi

# ---------------------------------------------------------------------------
# 3) No splitter available.
# ---------------------------------------------------------------------------
echo "!! No split method available (need tmux, or gnome-terminal+xdotool on X11)." >&2
echo "   Install tmux for the best experience:  sudo apt-get install -y tmux" >&2
echo "   Then re-run this script." >&2
exit 1
