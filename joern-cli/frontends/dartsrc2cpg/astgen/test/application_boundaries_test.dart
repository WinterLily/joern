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
}
