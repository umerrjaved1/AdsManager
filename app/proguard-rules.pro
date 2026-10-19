# The sample release build minifies so the library's consumer rules are actually
# exercised. The ads SDK, Firebase and this library already contribute their own
# rules; nothing here should blanket-keep those packages.

-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
