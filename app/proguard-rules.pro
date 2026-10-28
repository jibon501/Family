# সব ক্লাসের নাম ছোট করে এক প্যাকেজে আনা হবে
-repackageclasses ''
-allowaccessmodification
-optimizationpasses 5

# ক্র্যাশ রিপোর্টে ফাইল/লাইন নম্বর রাখা হবে না (রিভার্স করা কঠিন করতে)
-renamesourcefileattribute SourceFile

# Room এর এন্টিটি
-keep class com.example.familytree.Person { *; }
