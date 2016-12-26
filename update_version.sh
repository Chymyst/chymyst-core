#!/bin/bash

VERSION=`git tag --sort=-taggerdate|head -1`

function safe_move {
  local file1="$1" file2="$2"

  local new_lines=`wc -l < "$file1"`
  local old_lines=`wc -l < "$file2"`
  if [ $old_lines -eq $new_lines ]
  then
    mv "$file1" "$file2"
  else
    echo "Error: '$file1' is somehow corrupt"
  fi
}

# README has version at two places

sed -e 's|\(img.shields.io/badge/version-\)[v.0-9]*\(-blue.svg\)|\1'$VERSION'\2|; s|^\(Current released* version is `\)[v.0-9]*\(`.\)$|\1'$VERSION'\2|' < README.md > README.md.new

safe_move README.md.new README.md

# build.sbt has version := "0.1.3",

sed -e 's|^\( * version := "\)[.0-9]*\(", *\)$|\1'$VERSION'\2|' < build.sbt > build.sbt.new

safe_move build.sbt.new build.sbt

# Check whether the version history has been updated, warn otherwise.

grep -q "^- $VERSION " docs/joinrun.md || echo "Warning: docs/joinrun.md does not seem to have information about the current version $VERSION."
