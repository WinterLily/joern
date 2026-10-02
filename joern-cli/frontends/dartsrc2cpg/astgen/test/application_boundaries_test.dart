import 'dart:io';

import 'package:test/test.dart';

Future<void> runApplication(String name, String program) async {
  final scratch = Directory('../../../../agents').absolute;
  final configuration = File(
    '${scratch.path}/application-corpus/$name/.dart_tool/package_config.json',
  );
  expect(
    configuration.existsSync(),
    isTrue,
    reason: 'Prepare the pinned Dart application corpus first',
  );
  final output = scratch.createTempSync('dart-application-oracle-');
  try {
    final source = File('${output.path}/main.dart')..writeAsStringSync(program);
    final result = await Process.run(Platform.resolvedExecutable, [
      '--packages=${configuration.path}',
      source.path,
    ]);
    expect(result.exitCode, 0, reason: '${result.stdout}\n${result.stderr}');
  } finally {
    output.deleteSync(recursive: true);
  }
}

void main() {
  final skip = Platform.environment['DART_APPLICATION_TESTS'] != '1'
      ? 'Set DART_APPLICATION_TESTS=1 after preparing the pinned applications'
      : false;
  test(
    'pinned Sass keeps parser text, utility values and independent options',
    () => runApplication('dart-sass', r'''
import 'package:sass/sass.dart' as sass;
import 'package:sass/src/ast/sass/statement/stylesheet.dart';
import 'package:sass/src/utils.dart' as utils;
import 'package:term_glyph/term_glyph.dart' as glyph;

void check(bool value, String reason) {
  if (!value) throw StateError(reason);
}

void main() {
  for (final color in ['red', 'blue', 'lime']) {
    final source = 'a { color: $color; }';
    for (final syntax in [sass.Syntax.scss, sass.Syntax.css]) {
      final parsed = Stylesheet.parse(source, syntax);
      check(parsed.span.text == source, 'Lost parser source');
      for (final verbose in [false, true]) {
        final result = sass.compileString(source, syntax: syntax, verbose: verbose);
        check(result == 'a {\n  color: $color;\n}', 'Mixed source or verbosity');
        check(sass.compileStringToResult(source, syntax: syntax, verbose: verbose).css == result, 'Mixed public forwarding');
      }
    }
    check(Stylesheet.parseScss(source).span.text == source, 'Lost SCSS source');
    check(Stylesheet.parseCss(source).span.text == source, 'Lost CSS source');
  }
  for (final (source, expected) in [
    ('', ''),
    (' \t\n\r\f ', ''),
    (' alpha ', 'alpha'),
    ('\u00a0alpha\u00a0', '\u00a0alpha\u00a0'),
  ]) {
    for (final excludeEscape in [false, true]) {
      check(utils.trimAscii(source, excludeEscape: excludeEscape) == expected,
          'Lost ASCII trim boundary');
    }
  }
  check(utils.trimAscii(r' a\ ') == r'a\', 'Default escape trim');
  check(utils.trimAscii(r' a\ ', excludeEscape: true) == r'a\ ', 'Escape preservation');
  for (final name in ['cat', 'dog']) {
    for (final count in [0, 1, 2]) {
      check(utils.pluralize(name, count) == (count == 1 ? name : '${name}s'), 'Mixed name/count');
      check(utils.pluralize(name, count, plural: 'children') == (count == 1 ? name : 'children'), 'Mixed explicit plural');
    }
  }
  check(utils.indent('', 2) == '  ', 'Empty indentation');
  check(utils.indent('first\nsecond\n', 2) == '  first\n  second\n  ', 'Lost lines');
  check(utils.indent('first\nsecond', 0) == 'first\nsecond', 'Zero indentation');
  for (final ascii in [false, true]) {
    glyph.ascii = ascii;
    final bullet = ascii ? '*' : '•';
    check(utils.bulletedList([]) == '', 'Empty bullets');
    check(utils.bulletedList(['only']) == '$bullet only\n  ', 'Single bullet with empty rest');
    check(utils.bulletedList(['first\nsecond', 'third']) ==
        '$bullet first\n  second\n$bullet third\n  ', 'Lost multiline bullets');
  }
}
'''),
    skip: skip,
  );
  test(
    'pinned LocalSend URI class and filename extension keep independent inputs',
    () async {
      final source = File(
        '../../../../agents/application-corpus/localsend/app/lib/util/native/content_uri_helper.dart',
      ).readAsStringSync();
      final start = source.indexOf('class ContentUriHelper {');
      final end = source.indexOf('class AndroidUriContentStreamResolver');
      expect(start, greaterThanOrEqualTo(0));
      expect(end, greaterThan(start));
      await runApplication(
        'localsend/app',
        "import 'package:localsend_app/util/file_path_helper.dart';\n${source.substring(start, end)}"
            r'''
void main() {
  for (final (suffix, encoded) in <(String?, String)>[
    (null, ''), ('', '%2F'), ('first/second', '%2Ffirst%2Fsecond'),
    ('a b', '%2Fa%20b'), ('é', '%2F%C3%A9'),
  ]) {
    const tree = 'content://host/tree/primary%3ADocuments';
    final value = ContentUriHelper.convertTreeUriToDocumentUri(treeUri: tree, suffix: suffix);
    if (value != '$tree/document/primary%3ADocuments$encoded') {
      throw StateError('Lost suffix or mixed tree prefix');
    }
  }
  for (final (uri, path) in <(String, String?)>[
    ('content://host/tree/primary%3ADocuments', 'primary:Documents'),
    ('content://host/tree/primary%3ADocuments%2Fsub', 'primary:Documents/sub'),
    ('content://host/document/primary%3ADocuments', null),
    ('', null),
  ]) {
    if (ContentUriHelper.getPathFromTreeUri(uri) != path) {
      throw StateError('Lost decoding or null branch');
    }
  }
  for (final (path, extension) in [
    ('photo.JPG', 'jpg'), ('plain', ''), ('.hidden', 'hidden'), ('trailing.', ''),
  ]) {
    for (final name in ['first', 'second']) {
      if (path.extension != extension || path.withFileNameKeepExtension(name) != '$name.$extension') {
        throw StateError('Mixed name, receiver or extension');
      }
    }
  }
}
''',
      );
    },
    skip: skip,
  );
  test(
    'pinned Saber stored-byte codec keeps content, null and alias branches',
    () => runApplication('saber', r'''
import 'dart:convert';
import 'dart:typed_data';
import 'package:saber/data/codecs/base64_codec.dart';
void main() {
  final codec = Base64StowCodec();
  for (final values in <List<int>>[[], [0], [255, 1, 0]]) {
    final input = Uint8List.fromList(values);
    final encoded = codec.encoder.convert(input)!;
    if (encoded != base64Encode(values)) throw StateError('Lost encoded bytes');
    final decoded = codec.decoder.convert(encoded)!;
    if (decoded.length != input.length) throw StateError('Lost decoded length');
    for (var i=0;i<input.length;i++) {
      if (decoded[i] != input[i]) throw StateError('Lost decoded bytes');
    }
    if (!identical(codec.decoder.convert(input),input)) throw StateError('Lost byte identity');
  }
  if (codec.encoder.convert(null)!=null || codec.decoder.convert(null)!=null) {
    throw StateError('Lost null branch');
  }
  var rejected = false;
  try { codec.decoder.convert(1); } on ArgumentError { rejected = true; }
  if (!rejected) throw StateError('Unsupported decoder type accepted');
  final first=Uint8List.fromList([1,2]);
  final second=Uint8List.fromList([3,4]);
  codec.decoder.convert(first)![0]=255;
  if (first[0]!=255 || second[0]!=3) throw StateError('Mixed independent byte aliases');
}
'''),
    skip: skip,
  );
}
