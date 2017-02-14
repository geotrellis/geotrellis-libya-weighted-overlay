#!/usr/bin/env bash
set -euo pipefail
IFS=$'\n\t'

function render_template() {
eval "cat <<EOF
$(<$1)
EOF
"
}

FILE=$1
TEMPLATE=$2
echo "Template config: $FILE from $TEMPLATE"
render_template $TEMPLATE > $FILE
