-keep class com.google.mediapipe.** { *; }
-keep class com.google.protobuf.** { *; }

# Классы профайлера MediaPipe, которых нет в tasks-vision: их вызовы не используются.
-dontwarn com.google.mediapipe.proto.CalculatorProfileProto$CalculatorProfile
-dontwarn com.google.mediapipe.proto.GraphTemplateProto$CalculatorGraphTemplate
