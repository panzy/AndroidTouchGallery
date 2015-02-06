#!/bin/bash
#
# make jar.

VER="`git log --oneline -n 1 | cut -d ' ' -f 1`"
FILE=touch-gallery-$VER.jar
cd out/production/library/
jar cvf $FILE .
cd -

mv out/production/library/$FILE ./

