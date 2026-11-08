import 'dart:async';
import 'package:flutter/widgets.dart';
import 'package:flutter/services.dart';
import 'app.dart';

void main() {
  WidgetsFlutterBinding.ensureInitialized();
  // Compose owns page/panel navigation and the Activity's system back handling.
  unawaited(SystemNavigator.setFrameworkHandlesBack(false));
  runApp(const StarMarkApp());
}
