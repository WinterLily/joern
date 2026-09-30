import 'dart:convert';
import 'dart:io';

import 'package:dart_astgen/exporter.dart';
import 'package:path/path.dart' as p;
import 'package:test/test.dart';

void main() {
  late Directory project;
  final scratch = Directory(p.absolute('../../../../agents'));

  void write(String name, String content) {
    final file = File(p.join(project.path, name));
    file.parent.createSync(recursive: true);
    file.writeAsStringSync(content);
  }

  Future<List<Map<String, Object?>>> export({String? input}) =>
      exportProject(root: project.path, input: input).toList();

  List<Map<String, Object?>> units(List<Map<String, Object?>> records) =>
      records.where((record) => record['record'] == 'unit').toList();

  List<Map<String, Object?>> entries(Map<String, Object?> unit, String key) =>
      (unit[key] as List).cast<Map<String, Object?>>();

  setUp(() {
    scratch.createSync(recursive: true);
    project = scratch.createTempSync('dart astgen ');
    write(
      'pubspec.yaml',
      "name: fixture\nenvironment:\n  sdk: '>=3.9.0 <4.0.0'\n",
    );
  });

  tearDown(() => project.deleteSync(recursive: true));

  test('existing package configuration resolves package imports', () async {
    write(
      '.dart_tool/package_config.json',
      jsonEncode({
        'configVersion': 2,
        'packages': [
          {
            'name': 'fixture',
            'rootUri': '../',
            'packageUri': 'lib/',
            'languageVersion': '3.9',
          },
        ],
      }),
    );
    write('lib/helper.dart', 'String relay(String value) => value;');
    write(
      'main.dart',
      "import 'package:fixture/helper.dart';\nString main() => relay('x');",
    );
    final files = units(await export());
    expect(files.map((u) => u['status']), everyElement('resolved'));
    final helper = files.singleWhere((u) => u['file'] == 'lib/helper.dart');
    final main = files.singleWhere((u) => u['file'] == 'main.dart');
    final declaration = entries(
      helper,
      'nodes',
    ).singleWhere((n) => n['kind'] == 'FunctionDeclaration')['declaration'];
    expect(
      entries(
        main,
        'nodes',
      ).singleWhere((n) => n['kind'] == 'MethodInvocation')['target'],
      declaration,
    );
  });

  test('cross-file targets, inferred types and stable output', () async {
    write('lib/helper.dart', 'String relay(String value) => value;');
    write('bin/main.dart', """
import '../lib/helper.dart';
void sink(String value) {}
void main(List<String> args) {
  final input = args[0];
  sink(relay(input));
}
""");
    final records = await export();
    expect(records.first['protocolVersion'], 1);
    expect(records.first['sdkVersion'], '3.9.2');
    expect(records.last, {'record': 'summary', 'files': 2});
    final files = units(records);
    expect(files.map((unit) => unit['status']), everyElement('resolved'));
    expect(
      files.map((unit) => unit['unsupportedKinds']),
      everyElement(isEmpty),
    );
    final helper = files.singleWhere(
      (unit) => unit['file'] == 'lib/helper.dart',
    );
    final main = files.singleWhere((unit) => unit['file'] == 'bin/main.dart');
    final declaration = entries(
      helper,
      'nodes',
    ).singleWhere((node) => node['kind'] == 'FunctionDeclaration');
    final call = entries(main, 'nodes').singleWhere(
      (node) =>
          node['kind'] == 'MethodInvocation' &&
          node['target'] == declaration['declaration'],
    );
    expect(call['type'], 'String');
    expect(
      entries(main, 'symbols').singleWhere((s) => s['name'] == 'input')['type'],
      'String',
    );
    expect(jsonEncode(await export()), jsonEncode(records));
    final single = units(
      await export(input: p.join(project.path, 'bin/main.dart')),
    ).single;
    expect(jsonEncode(single), jsonEncode(main));

    final original = project;
    final relocated = scratch.createTempSync('dart relocated ');
    try {
      for (final file in original.listSync(recursive: true).whereType<File>()) {
        final copy = File(
          p.join(relocated.path, p.relative(file.path, from: original.path)),
        );
        copy.parent.createSync(recursive: true);
        file.copySync(copy.path);
      }
      project = relocated;
      expect(jsonEncode(await export()), jsonEncode(records));
    } finally {
      project = original;
      relocated.deleteSync(recursive: true);
    }
  });

  test(
    'named arguments retain source order and bind to declaration order',
    () async {
      write('main.dart', """
String combine({String first = 'a', required String second, int count = 1}) => first;
void main() { combine(second: 'b', first: 'a'); }
""");
      final unit = units(await export()).single;
      expect(unit['status'], 'resolved');
      final symbols = entries(unit, 'symbols');
      final function = symbols.singleWhere((s) => s['name'] == 'combine');
      final parameters = function['parameters'] as List;
      final args = entries(
        unit,
        'nodes',
      ).singleWhere((n) => n['kind'] == 'ArgumentList');
      final bindings = entries(args, 'bindings');
      expect(bindings.map((b) => b['parameter']), [
        parameters[1],
        parameters[0],
      ]);
      expect(
        bindings[0]['offset'] as int,
        lessThan(bindings[1]['offset'] as int),
      );
      final omitted = symbols.singleWhere((s) => s['id'] == parameters[2]);
      expect(omitted['defaultValue'], '1');
      expect(omitted['required'], false);
    },
  );

  test('UTF-16 offsets preserve Unicode and CRLF source slices', () async {
    const source = "void main() { final emoji = '😀'; print(emoji); }\r\n";
    write('unicode.dart', source);
    final unit = units(await export()).single;
    expect(unit['source'], source);
    final identifier = entries(unit, 'nodes').singleWhere(
      (n) => n['kind'] == 'SimpleIdentifier' && n['name'] == 'emoji',
    );
    final offset = identifier['offset'] as int;
    expect(offset, source.lastIndexOf('emoji'));
    expect(identifier['column'], offset + 1);
    expect(
      source.substring(offset, offset + (identifier['length'] as int)),
      'emoji',
    );
  });

  test(
    'missing dependencies and malformed files retain useful partial ASTs',
    () async {
      write(
        'missing.dart',
        "import 'package:absent/absent.dart';\nvoid main() { absent(); }",
      );
      write('broken.dart', 'String relay(String value) { return value;');
      write('valid.dart', 'int answer() => 42;');
      final files = units(await export());
      expect(files, hasLength(3));
      for (final file in files.where((u) => u['file'] != 'valid.dart')) {
        expect(file['status'], 'partial');
        expect(file['diagnostics'], isNotEmpty);
        expect(
          entries(
            file,
            'nodes',
          ).where((n) => n['kind'] == 'FunctionDeclaration'),
          isNotEmpty,
        );
      }
      expect(
        files.singleWhere((u) => u['file'] == 'valid.dart')['status'],
        'resolved',
      );
      final missing = files.singleWhere((u) => u['file'] == 'missing.dart');
      expect(
        entries(
          missing,
          'nodes',
        ).singleWhere((n) => n['kind'] == 'MethodInvocation')['target'],
        isNull,
      );
    },
  );

  test(
    'part references share declaration identities and library context',
    () async {
      write(
        'lib/main.dart',
        "part 'helper.dart';\nString main() => relay('x');",
      );
      write(
        'lib/helper.dart',
        "part of 'main.dart';\nString relay(String value) => value;",
      );
      final files = units(await export());
      expect(files.map((u) => u['status']), everyElement('resolved'));
      expect(files.map((u) => u['library']), everyElement('lib/main.dart'));
      final helper = files.singleWhere((u) => u['file'] == 'lib/helper.dart');
      final main = files.singleWhere((u) => u['file'] == 'lib/main.dart');
      final declaration = entries(
        helper,
        'nodes',
      ).singleWhere((n) => n['kind'] == 'FunctionDeclaration')['declaration'];
      expect(
        entries(
          main,
          'nodes',
        ).singleWhere((n) => n['kind'] == 'MethodInvocation')['target'],
        declaration,
      );
    },
  );

  test(
    'unsupported constructs and existing generated sources are visible',
    () async {
      write(
        'widget.g.dart',
        "import 'package:flutter/widgets.dart';\nclass App extends StatelessWidget { Widget build(BuildContext context) => const Text('hello'); }",
      );
      final unit = units(await export()).single;
      expect(unit['file'], 'widget.g.dart');
      expect(unit['status'], 'partial');
      expect(unit['unsupportedKinds'], contains('ClassDeclaration'));
      expect(unit['diagnostics'], isNotEmpty);
    },
  );

  test('empty inputs and invalid SDK/input paths are explicit', () async {
    expect(await export(), hasLength(2));
    expect(
      exportProject(
        root: project.path,
        sdkPath: p.join(project.path, 'missing'),
      ).toList(),
      throwsArgumentError,
    );
    expect(
      exportProject(
        root: project.path,
        input: p.join(project.path, 'missing.dart'),
      ).toList(),
      throwsArgumentError,
    );
    expect(
      exportProject(
        root: project.path,
        input: p.dirname(project.path),
      ).toList(),
      throwsArgumentError,
    );
  });

  test('CLI emits JSONL and rejects invalid usage', () async {
    write('main.dart', 'void main() {}');
    final result = await Process.run(Platform.resolvedExecutable, [
      'run',
      'bin/dart_astgen.dart',
      project.path,
    ]);
    expect(result.exitCode, 0, reason: '${result.stderr}');
    final records = LineSplitter.split(
      result.stdout as String,
    ).map(jsonDecode).toList();
    expect(records.first['record'], 'header');
    expect(records.last['record'], 'summary');
    final invalid = await Process.run(Platform.resolvedExecutable, [
      'run',
      'bin/dart_astgen.dart',
    ]);
    expect(invalid.exitCode, 64);
    expect(invalid.stdout, isEmpty);
  });
}
