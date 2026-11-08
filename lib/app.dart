import 'dart:async';
import 'package:flutter/widgets.dart';
import 'package:watermark_engine/watermark_engine.dart';
import 'editor/editor_controller.dart';
import 'editor/watermark_renderer.dart';
import 'ui/native_ui_bridge.dart';

/// Flutter renders the photo surface; all application UI lives in Compose Miuix.
class StarMarkApp extends StatefulWidget {
  final bool testMode;
  final EditorController? controller;
  final NativeUiBridge? uiBridge;
  const StarMarkApp({
    super.key,
    this.testMode = false,
    this.controller,
    this.uiBridge,
  });

  @override
  State<StarMarkApp> createState() => _StarMarkAppState();
}

class _StarMarkAppState extends State<StarMarkApp> with WidgetsBindingObserver {
  late final EditorController photoEditor =
      widget.uiBridge?.editor ??
      widget.controller ??
      EditorController(testMode: widget.testMode);
  late final NativeUiBridge bridge =
      widget.uiBridge ?? NativeUiBridge(photoEditor);
  EditorController get editor => bridge.editor;
  EditDocument? _gestureStart;
  Offset? _focalStart;
  int? _gestureRevision;
  bool _original = false;

  @override
  void initState() {
    super.initState();
    assert(
      widget.controller == null ||
          widget.uiBridge == null ||
          identical(widget.controller, widget.uiBridge!.editor),
      'The injected bridge and canvas must use the same EditorController.',
    );
    WidgetsBinding.instance.addObserver(this);
    bridge.addListener(_changed);
    bridge.attach();
    unawaited(_initialize());
  }

  Future<void> _initialize() async {
    if (widget.controller == null && widget.uiBridge == null) {
      await editor.initialize();
    }
    if (!mounted) return;
    await bridge.initialize();
    bridge.publish();
  }

  void _changed() {
    if (_gestureStart != null &&
        (_gestureRevision != editor.revision || !editor.history.interacting)) {
      _gestureStart = null;
      _focalStart = null;
    }
    if (mounted) setState(() {});
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.inactive ||
        state == AppLifecycleState.paused) {
      _gestureStart = null;
      _focalStart = null;
      _original = false;
      bridge.cancelInteraction();
      unawaited(editor.saveDraft());
    }
  }

  @override
  Widget build(BuildContext context) => Directionality(
    textDirection: TextDirection.ltr,
    child: LayoutBuilder(
      builder: (context, constraints) {
        final resources = editor.renderAssets;
        if (resources == null || editor.loading) return const SizedBox.expand();
        final layout = editor.renderLayout;
        final ratio = layout.width / layout.height;
        var width = constraints.maxWidth;
        var height = width / ratio;
        if (height > constraints.maxHeight) {
          height = constraints.maxHeight;
          width = height * ratio;
        }
        if (!width.isFinite || !height.isFinite || width <= 0 || height <= 0) {
          return const SizedBox.shrink();
        }
        return Center(
          child: SizedBox(
            width: width,
            height: height,
            child: IgnorePointer(
              ignoring: bridge.busy,
              child: GestureDetector(
                key: const Key('watermark-canvas'),
                behavior: HitTestBehavior.opaque,
                onLongPressStart: (_) => setState(() => _original = true),
                onLongPressEnd: (_) => setState(() => _original = false),
                onLongPressCancel: () => setState(() => _original = false),
                onScaleStart: (details) {
                  if (bridge.busy ||
                      editor.history.interacting ||
                      editor.document.xiaomiConfig != null) {
                    return;
                  }
                  editor.cancelPendingFont();
                  _gestureStart = editor.document;
                  _gestureRevision = editor.revision;
                  _focalStart = details.localFocalPoint;
                  editor.begin();
                },
                onScaleUpdate: (details) {
                  final start = _gestureStart;
                  if (start == null ||
                      bridge.busy ||
                      editor.document.xiaomiConfig != null ||
                      _gestureRevision != editor.revision) {
                    return;
                  }
                  final delta = details.localFocalPoint - _focalStart!;
                  editor.previewEdit(
                    start.copyWith(
                      x: (start.x + delta.dx / width).clamp(.02, .98),
                      y: (start.y + delta.dy / height).clamp(.02, .98),
                      scale: (start.scale * details.scale).clamp(.3, 2.5),
                    ),
                  );
                },
                onScaleEnd: (_) {
                  if (_gestureStart == null) return;
                  _gestureStart = null;
                  _focalStart = null;
                  if (!bridge.busy && _gestureRevision == editor.revision) {
                    editor.commit('移动 / 缩放水印');
                  } else {
                    editor.cancel();
                  }
                },
                child: CustomPaint(
                  painter: WatermarkPainter(
                    editor.renderDocument,
                    editor.renderAsset,
                    resources,
                    original: _original,
                    pixelRatio: View.of(context).devicePixelRatio,
                  ),
                  child: const SizedBox.expand(),
                ),
              ),
            ),
          ),
        );
      },
    ),
  );

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    bridge.removeListener(_changed);
    if (widget.uiBridge == null) bridge.dispose();
    if (widget.controller == null && widget.uiBridge == null) {
      photoEditor.dispose();
    }
    super.dispose();
  }
}
