#!/bin/bash

VERSION=$1

if [[ -z $VERSION ]]
then
    echo "Usage: $0 <version>"
    echo "where <version> can be 1.0.4 or so"
    exit 1
fi

if git tag --sort=-taggerdate | fgrep -q $VERSION
then
    echo Error: Tag $VERSION already exists.
    exit 1
fi

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

if grep -q "^- $VERSION " docs/roadmap.md
then
    echo "Preparing new release $VERSION"
else
    echo "Error: docs/roadmap.md does not seem to have information about the current version $VERSION."
    exit 1
fi

git add build.sbt README.md
git commit -m "update for release $VERSION"
git tag $VERSION

echo "All done, now push to github using git push origin <branch> --tags"
