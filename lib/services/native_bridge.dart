import 'package:flutter/services.dart';

class NativeBridge {
  static const channel = MethodChannel('dev.starmark/native');
  static Future<T?> call<T>(String method, [Map<String, dynamic>? args]) =>
      channel.invokeMethod<T>(method, args);
}
