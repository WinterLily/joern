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

  test(
    'distinguish list, set and map collection literals after resolution',
    () async {
      write(
        'main.dart',
        'void main() { print([1]); print({1}); print({1: 2}); }',
      );
      final unit = units(await export()).single;
      expect(unit['status'], 'resolved');
      expect(
        entries(unit, 'nodes')
            .where((node) => node.containsKey('collectionKind'))
            .map((node) => node['collectionKind']),
        ['list', 'set', 'map'],
      );
    },
  );

  test('keep separate logical-or joins distinct within one function', () async {
    write('main.dart', """
void choose(Object value) {
  if (value case [var item] || (var item,)) { print(item); }
  if (value case [var item] || (var item,)) { print(item); }
}
""");
    final unit = units(await export()).single;
    expect(unit['status'], 'resolved');
    final bindings = entries(unit, 'nodes')
        .where((node) => node['kind'] == 'DeclaredVariablePattern')
        .map((node) => node['declaration'])
        .toList();
    expect(bindings.length, 4);
    expect(bindings[0], bindings[1]);
    expect(bindings[2], bindings[3]);
    expect(bindings[0], isNot(bindings[2]));
  });

  test(
    'export static lazy accessors separately from constant storage',
    () async {
      write('main.dart', """
String build() => 'value';
final String deferred = build();
const String fixed = 'constant';
class Store { static String value = build(); }
""");
      final unit = units(await export()).single;
      expect(unit['status'], 'resolved');
      final symbols = entries(unit, 'symbols');
      final deferred = symbols.singleWhere(
        (s) => s['kind'] == 'TOP_LEVEL_VARIABLE' && s['name'] == 'deferred',
      );
      expect(deferred['const'], isFalse);
      expect(deferred['late'], isFalse);
      expect(deferred['getter'], contains('GETTER:deferred'));
      expect(deferred['setter'], isNull);
      final field = symbols.singleWhere(
        (s) => s['kind'] == 'FIELD' && s['name'] == 'value',
      );
      expect(field['getter'], contains('GETTER:value'));
      expect(field['setter'], contains('SETTER:value'));
      expect(
        symbols.singleWhere(
          (s) => s['kind'] == 'TOP_LEVEL_VARIABLE' && s['name'] == 'fixed',
        )['const'],
        isTrue,
      );
    },
  );

  test('retain late storage and synthetic accessor identities', () async {
    write('main.dart', """
class Box { late final int? initialized = null; late final int assigned; }
void main() { late int local = 1; print(local); }
""");
    final unit = units(await export()).single;
    expect(unit['status'], 'resolved');
    final symbols = entries(unit, 'symbols');
    final initialized = symbols.singleWhere(
      (s) => s['kind'] == 'FIELD' && s['name'] == 'initialized',
    );
    final assigned = symbols.singleWhere(
      (s) => s['kind'] == 'FIELD' && s['name'] == 'assigned',
    );
    expect(initialized['late'], isTrue);
    expect(initialized['getter'], contains('GETTER:initialized'));
    expect(initialized['setter'], isNull);
    expect(assigned['setter'], contains('SETTER:assigned'));
    expect(
      symbols.singleWhere(
        (s) => s['kind'] == 'LOCAL_VARIABLE' && s['name'] == 'local',
      )['late'],
      isTrue,
    );
  });

  test(
    'export operator dispatch separately from indexed read and write targets',
    () async {
      write('main.dart', """
class Value { Value operator +(int amount) => this; }
class Store {
  Value operator [](int index) => Value();
  void operator []=(int index, Value value) {}
}
void main() { final store = Store(); store[0] += 1; store[1]++; }
""");
      final unit = units(await export()).single;
      expect(unit['status'], 'resolved');
      final nodes = entries(unit, 'nodes');
      final symbols = {
        for (final symbol in entries(unit, 'symbols')) symbol['id']: symbol,
      };
      for (final kind in ['AssignmentExpression', 'PostfixExpression']) {
        final update = nodes.singleWhere((node) => node['kind'] == kind);
        expect(symbols[update['operatorTarget']]!['name'], '+');
        expect(symbols[update['read']]!['name'], '[]');
        expect(symbols[update['write']]!['name'], '[]=');
      }
    },
  );

  test(
    'select VM and web conditional imports from the pinned SDK library set',
    () async {
      write(
        'main.dart',
        "import 'fallback.dart' if (dart.library.io) 'vm.dart' if (dart.library.html) 'web.dart'; void main() { chosen(); }",
      );
      for (final name in ['fallback', 'vm', 'web']) {
        write('$name.dart', "String chosen() => '$name';");
      }
      for (final environment in ['analyzer-default', 'vm', 'web']) {
        final records = await exportProject(
          root: project.path,
          environment: environment,
        ).toList();
        expect(records.first['conditionalEnvironment'], environment);
        final unit = units(
          records,
        ).singleWhere((unit) => unit['file'] == 'main.dart');
        final expected = environment == 'analyzer-default'
            ? 'fallback'
            : environment;
        final call = entries(
          unit,
          'nodes',
        ).singleWhere((node) => node['kind'] == 'MethodInvocation');
        expect(call['target'], startsWith('$expected.dart#'));
        expect(unit['status'], 'resolved');
      }
      expect(
        exportProject(root: project.path, environment: 'unknown').toList(),
        throwsArgumentError,
      );
    },
  );

  test(
    'export aliases, type literals, implicit call tear-offs and null-aware elements',
    () async {
      write('main.dart', """
typedef Callback<T extends num> = T Function(T value);
typedef Legacy<T>(T value);
class Callable { String call(String value) => value; }
void main() {
  final String Function(String) callback = Callable();
  final type = List<String>;
  String? value;
  final list = [?value];
  final map = {?value: ?value};
  callback('text');
}
""");
      final unit = units(await export()).single;
      expect(unit['unsupportedKinds'], isEmpty);
      final nodes = entries(unit, 'nodes');
      for (final kind in [
        'GenericTypeAlias',
        'FunctionTypeAlias',
        'TypeLiteral',
        'ImplicitCallReference',
        'NullAwareElement',
      ]) {
        expect(nodes.any((node) => node['kind'] == kind), isTrue, reason: kind);
      }
      final entry = nodes.singleWhere(
        (node) => node['kind'] == 'MapLiteralEntry',
      );
      expect(entry['nullAwareKey'], isTrue);
      expect(entry['nullAwareValue'], isTrue);
    },
  );

  test(
    'export update targets and executable enum and labeled syntax',
    () async {
      write('main.dart', '''enum Mode {
      first(1), second(2);
      final int value;
      const Mode(this.value) : assert(value > 0);
      int read() => value;
    }
    void main() {
      var counter = 0;
      outer: while (counter < 3) {
        counter++;
        --counter;
        assert(counter >= 0, 'negative');
        break outer;
      }
    }
    ''');
      final unit = units(await export()).single;
      final nodes = entries(unit, 'nodes');
      expect(unit['status'], 'resolved');
      expect(unit['unsupportedKinds'], isEmpty);
      final updates = nodes.where(
        (n) =>
            n['kind'] == 'PrefixExpression' || n['kind'] == 'PostfixExpression',
      );
      expect(updates, hasLength(2));
      for (final update in updates) {
        expect(update['read'], isNotNull);
        expect(update['write'], update['read']);
      }
      expect(
        nodes.where((n) => n['kind'] == 'EnumConstantDeclaration'),
        everyElement(containsPair('target', isNotNull)),
      );
      expect(
        nodes.singleWhere((n) => n['kind'] == 'LabeledStatement')['labels'],
        ['outer'],
      );
      expect(
        nodes.where((n) => n['kind'] == 'AssertInitializer'),
        hasLength(1),
      );
      expect(nodes.where((n) => n['kind'] == 'AssertStatement'), hasLength(1));
    },
  );

  test('an unreadable source retains a diagnostic and other files', () async {
    File(p.join(project.path, 'broken.dart')).writeAsBytesSync([0xff, 0xfe]);
    write('good.dart', 'void good() {}');
    final records = await export();
    expect(records.last['files'], 2);
    final files = units(records);
    final broken = files.singleWhere((unit) => unit['file'] == 'broken.dart');
    expect(broken['status'], 'parsed');
    expect(
      entries(broken, 'diagnostics').first['code'],
      'resolution_unavailable',
    );
    expect(
      files.singleWhere((unit) => unit['file'] == 'good.dart')['status'],
      'resolved',
    );
  });

  test('explicitly scanned analysis-option exclusions still resolve', () async {
    write(
      'analysis_options.yaml',
      'analyzer:\n  exclude:\n    - excluded.dart\n',
    );
    write('excluded.dart', 'void excluded() {}');
    write('good.dart', 'void good() {}');
    final files = units(await export());
    expect(files, hasLength(2));
    expect(files.every((unit) => unit['status'] == 'resolved'), isTrue);
    expect(
      entries(
        files.singleWhere((unit) => unit['file'] == 'excluded.dart'),
        'nodes',
      ).any((node) => node['kind'] == 'FunctionDeclaration'),
      isTrue,
    );
  });

  test(
    'cascade markers distinguish nested implicit calls and symbols',
    () async {
      write('main.dart', r"""
      class Owner {
        Object value() => #ready;
        void take(Object value) {}
        void run() { Owner()..take(value()); }
      }
    """);
      final unit = units(await export()).single;
      final nodes = entries(unit, 'nodes');
      final calls = nodes
          .where((n) => n['kind'] == 'MethodInvocation')
          .toList();
      expect(calls.map((n) => n['cascaded']), [true, false]);
      expect(nodes.where((n) => n['kind'] == 'SymbolLiteral'), hasLength(1));
      expect(unit['unsupportedKinds'], isEmpty);
    },
  );

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
      expect(
        entries(unit, 'nodes').where((n) => n['kind'] == 'ClassDeclaration'),
        hasLength(1),
      );
      expect(unit['diagnostics'], isNotEmpty);
    },
  );

  test(
    'core syntax exports roles, dispatch and constructor identities',
    () async {
      write('main.dart', r"""
class Base { Base(); }
class Box<T> extends Base implements Comparable<Box<T>> {
  T? item;
  Box(this.item) : super();
  Box.named(T value) : item = value;
  Box.redirect(T value) : this.named(value);
  factory Box.make(T value) = Box<T>.named;
  T? get value => item;
  set value(T? x) { item = x; }
  int compareTo(Box<T> other) => 0;
  static String echo(String value) => value;
}
void main() {
  final box = Box<String>('a');
  final callback = () => box.value;
  final tearoff = Box.echo;
  if (callback() != null) { box.value = tearoff('b'); } else { box.value = null; }
  while (box.value == null) { break; }
  do { box.value = 'c'; } while (false);
  for (var i = 0; i < 2; i++) { continue; }
  for (final value in ['a']) { box.value = value; }
  switch (box.value) { case 'a': break; default: break; }
  try { throw 'failure'; } catch (error, stack) { print(error); } finally { print('done'); }
  box..value = 'x'..compareTo(box);
  final list = [box.value]; final set = {box.value}; final map = {'key': box.value};
  print('value: ${box.value}'); print(box.value ?? 'default');
}
""");
      final unit = units(await export()).single;
      expect(unit['status'], 'resolved', reason: '${unit['diagnostics']}');
      expect(unit['unsupportedKinds'], isEmpty);
      final symbols = entries(unit, 'symbols');
      final box = symbols.singleWhere(
        (s) => s['kind'] == 'CLASS' && s['name'] == 'Box',
      );
      expect(box['superTypes'], containsAll(['Base', 'Comparable<Box<T>>']));
      final fields = symbols.where(
        (s) => s['kind'] == 'FIELD' && s['name'] == 'item',
      );
      expect(fields.single['type'], 'T?');
      expect(
        symbols.where(
          (s) => s['kind'] == 'CONSTRUCTOR' && s['file'] == 'main.dart',
        ),
        hasLength(5),
      );
      expect(
        entries(unit, 'nodes').where((n) => n['kind'] == 'SwitchCase'),
        hasLength(1),
      );
      expect(jsonEncode(await export()), jsonEncode(await export()));
    },
  );

  test(
    'modern syntax has explicit nodes and stable pattern identities',
    () async {
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
      write('main.dart', r"""
base mixin Echo { String echo(String value) => value; }
sealed class Root {}
final class Leaf extends Root with Echo {}
abstract interface class Contract { String run(); }
extension TextOps on String { String wrap() => this; }
extension type Label(String value) { String read() => value; }
(String, {int count}) record(String value) => (value, count: 2);
Future<String> relay(String value) async => value;
Iterable<String> syncValues(String value) sync* { yield value; yield* [value]; }
Stream<String> values(String value) async* { yield await relay(value); }
Future<void> main() async {
  final (text, count: count) = record('x');
  var left = ''; var right = 0;
  (left, right) = (text, count);
  if ((left, right) case (var value, > 0) when value.isNotEmpty) print(value);
  final selected = switch (text) { 'skip' => '', var value => value };
  final items = [...?nullable(), if (count > 0) selected, for (var i = 0; i < 2; i++) '$i'];
  for (final (a, b) in [(1, 2)]) print(a + b);
  await for (final value in values(text)) print(value);
  Object? obj = items;
  if (obj case [String first, ...var middle, String last]) print((first, middle, last));
  if (obj case {'key': var value}) print(value);
  if (obj case (String value) || [String value]) print(value);
  if (obj case Label(value: var contents)) print(contents);
  if (obj case var nonNull?) print(nonNull);
  if (obj case var asserted!) print(asserted);
  if (obj case var cast as String) print(cast);
  switch (obj) { case Label(value: var value) when value.isNotEmpty: print(value); break; default: break; }
  print(Leaf().echo('x'.wrap())); print(Label('y').read());
}
List<String>? nullable() => null;
""");
      final unit = units(await export()).single;
      expect(unit['status'], 'resolved', reason: '${unit['diagnostics']}');
      expect(unit['unsupportedKinds'], isEmpty);
      expect(unit['languageVersion'], '3.9.0');
      final nodes = entries(unit, 'nodes');
      expect(
        nodes.map((n) => n['kind']),
        containsAll([
          'RecordLiteral',
          'RecordPattern',
          'PatternAssignment',
          'SwitchExpression',
          'MixinDeclaration',
          'ExtensionDeclaration',
          'ExtensionTypeDeclaration',
          'SpreadElement',
          'IfElement',
          'ForElement',
          'AwaitExpression',
          'YieldStatement',
        ]),
      );
      expect(
        nodes.where((n) => n['kind'] == 'ForStatement' && n['await'] == true),
        hasLength(1),
      );
      final declared = nodes
          .where((n) => n['kind'] == 'DeclaredVariablePattern')
          .map((n) => n['declaration'])
          .toSet();
      expect(
        nodes
            .where(
              (n) => n['kind'] == 'SimpleIdentifier' && n['name'] == 'value',
            )
            .any((n) => declared.contains(n['reference'])),
        isTrue,
      );
      expect(jsonEncode(await export()), jsonEncode(await export()));
    },
  );

  test(
    'workspace packages, language versions, conditional imports and generated parts',
    () async {
      write(
        'pubspec.yaml',
        "name: workspace\nenvironment:\n  sdk: ^3.9.0\nworkspace:\n  - packages/app\n  - packages/helper\n",
      );
      for (final name in ['app', 'helper']) {
        write(
          'packages/$name/pubspec.yaml',
          'name: $name\nresolution: workspace\nenvironment:\n  sdk: ^3.9.0\n',
        );
      }
      write(
        '.dart_tool/package_config.json',
        jsonEncode({
          'configVersion': 2,
          'packages': [
            for (final name in ['app', 'helper'])
              {
                'name': name,
                'rootUri': '../packages/$name',
                'packageUri': 'lib/',
                'languageVersion': '3.9',
              },
          ],
        }),
      );
      write(
        'packages/helper/lib/helper.dart',
        "export 'stub.dart' if (dart.library.io) 'io.dart';",
      );
      write(
        'packages/helper/lib/stub.dart',
        "String relay(String value) => 'stub';",
      );
      write(
        'packages/helper/lib/io.dart',
        'String relay(String value) => value;',
      );
      write(
        'packages/app/lib/generated.g.dart',
        "// @dart=3.8\npart of 'main.dart';\nString generated(String value) => relay(value);",
      );
      write(
        'packages/app/lib/main.dart',
        "// @dart=3.8\nimport 'package:helper/helper.dart' if (dart.library.io) 'package:helper/io.dart';\npart 'generated.g.dart';\nvoid main() { print(generated('x')); }",
      );
      final files = units(await export());
      expect(files, hasLength(5));
      expect(
        files.map((u) => u['status']),
        everyElement('resolved'),
        reason: '$files',
      );
      final main = files.singleWhere(
        (u) => u['file'] == 'packages/app/lib/main.dart',
      );
      expect(main['languageVersion'], '3.8.0');
      expect(
        entries(
          main,
          'nodes',
        ).singleWhere((n) => n['kind'] == 'ImportDirective')['selectedUri'],
        'package:helper/helper.dart',
      );
      expect(
        (await export()).first['conditionalEnvironment'],
        'analyzer-default',
      );
      final generated = files.singleWhere(
        (u) => u['file'] == 'packages/app/lib/generated.g.dart',
      );
      expect(generated['library'], main['library']);
      final target = entries(
        generated,
        'nodes',
      ).singleWhere((n) => n['kind'] == 'MethodInvocation')['target'];
      expect(target, startsWith('package:helper/stub.dart#'));
      expect(files.expand((u) => u['unsupportedKinds'] as List), isEmpty);
      final single = units(
        await export(input: p.join(project.path, 'packages/app/lib/main.dart')),
      ).single;
      expect(single['status'], 'resolved');
      expect(single['languageVersion'], '3.8.0');
    },
  );

  test(
    'package language version rejects unavailable features explicitly',
    () async {
      write(
        '.dart_tool/package_config.json',
        jsonEncode({
          'configVersion': 2,
          'packages': [
            {
              'name': 'fixture',
              'rootUri': '../',
              'packageUri': 'lib/',
              'languageVersion': '2.19',
            },
          ],
        }),
      );
      write(
        'lib/main.dart',
        'void main() { final value = (1, 2); print(value); }',
      );
      final unit = units(await export()).single;
      expect(unit['languageVersion'], '2.19.0');
      expect(unit['status'], 'partial');
      expect(
        entries(unit, 'diagnostics').map((d) => d['code']),
        contains('EXPERIMENT_NOT_ENABLED'),
      );
      expect(
        entries(unit, 'nodes').map((n) => n['kind']),
        contains('FunctionDeclaration'),
      );
    },
  );

  test(
    'Flutter SDK resolves widget constructors, dart:ui and callbacks',
    () async {
      final fixture = Directory(p.absolute('../src/test/resources/flutter'));
      final config = File(
        Platform.environment['DART_FLUTTER_PACKAGE_CONFIG'] ??
            p.join(
              scratch.path,
              'flutter-fixture/.dart_tool/package_config.json',
            ),
      );
      expect(
        config.existsSync(),
        isTrue,
        reason:
            'Prepare the pinned Flutter fixture before setting DART_FLUTTER_TESTS',
      );
      write('.dart_tool/package_config.json', config.readAsStringSync());
      write(
        'lib/main.dart',
        File(p.join(fixture.path, 'lib/main.dart')).readAsStringSync(),
      );
      write(
        'lib/ui.dart',
        "import 'dart:ui';\nColor color() => const Color(0xff000000);",
      );
      final files = units(await export());
      expect(
        files.map((u) => u['status']),
        everyElement('resolved'),
        reason: '$files',
      );
      expect(files.expand((u) => u['unsupportedKinds'] as List), isEmpty);
      final main = files.singleWhere((u) => u['file'] == 'lib/main.dart');
      final creations = entries(
        main,
        'nodes',
      ).where((n) => n['kind'] == 'InstanceCreationExpression');
      expect(
        creations.map((n) => n['target']),
        everyElement(startsWith('package:flutter/')),
      );
      expect(creations, hasLength(5));
      final ui = files.singleWhere((u) => u['file'] == 'lib/ui.dart');
      expect(
        entries(ui, 'nodes').singleWhere(
          (n) => n['kind'] == 'InstanceCreationExpression',
        )['target'],
        startsWith('dart:ui/'),
      );
    },
    skip: Platform.environment['DART_FLUTTER_TESTS'] != '1',
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
