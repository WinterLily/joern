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

  test('synchronous iteration exports substituted member targets', () async {
    write(
      'main.dart',
      File(
        '../src/test/resources/semantics/synchronous_iteration.dart',
      ).readAsStringSync(),
    );
    final unit = units(await export()).single;
    expect(unit['status'], 'resolved');
    final symbols = {
      for (final value in entries(unit, 'symbols')) value['id']: value,
    };
    final loops = entries(
      unit,
      'nodes',
    ).where((node) => node['kind'] == 'ForEachParts').toList();
    expect(loops, hasLength(7));
    for (final loop in loops.take(5)) {
      for (final (key, name, kind) in [
        ('iteratorTarget', 'iterator', 'GETTER'),
        ('moveNextTarget', 'moveNext', 'METHOD'),
        ('currentTarget', 'current', 'GETTER'),
      ]) {
        final member = symbols[loop[key]]!;
        expect(member['name'], name);
        expect(member['kind'], kind);
      }
    }
    expect(loops[0]['iteratorType'], 'Cursor<String>');
    expect(loops[3]['iteratorType'], 'Cursor<(String, String)>');
    expect(loops[4]['iteratorType'], 'Iterator<String>');
    for (final loop in loops.skip(5)) {
      expect(loop.containsKey('iteratorTarget'), isFalse);
      expect(loop.containsKey('moveNextTarget'), isFalse);
      expect(loop.containsKey('currentTarget'), isFalse);
    }
  });

  test(
    'extension type dispatch retains instantiated representation constraints',
    () async {
      for (final name in [
        'extension_type_dispatch.dart',
        'synchronous_iteration.dart',
      ]) {
        write(
          name,
          File('../src/test/resources/semantics/$name').readAsStringSync(),
        );
      }
      final unit = units(
        await export(
          input: p.join(project.path, 'extension_type_dispatch.dart'),
        ),
      ).single;
      expect(unit['status'], 'resolved');
      final symbols = {
        for (final value in entries(unit, 'symbols')) value['id']: value,
      };
      for (final name in ['View', 'NestedView']) {
        final type = symbols.values.singleWhere(
          (s) => s['kind'] == 'EXTENSION_TYPE' && s['name'] == name,
        );
        expect(symbols[type['erasedTypeId']]!['name'], 'Store');
      }
      final generic = symbols.values.singleWhere(
        (s) => s['kind'] == 'EXTENSION_TYPE' && s['name'] == 'GenericView',
      );
      final parameter = symbols[generic['erasedTypeId']]!;
      expect(parameter['kind'], 'TYPE_PARAMETER');
      expect(parameter['name'], 'S');
      expect(parameter['owner'], generic['id']);
      final nodes = entries(unit, 'nodes');
      for (final name in [
        'direct',
        'nested',
        'concrete',
        'bounded',
        'nullable',
        'other',
        'own',
      ]) {
        final function = nodes.singleWhere(
          (n) => n['kind'] == 'FunctionDeclaration' && n['name'] == name,
        );
        final pending = <Map<String, Object?>>[function];
        final receivers = <Map<String, Object?>>[];
        while (pending.isNotEmpty) {
          final node = pending.removeLast();
          final children = (node['children'] as List)
              .cast<Map<String, Object?>>();
          if (node['kind'] == 'MethodInvocation') {
            receivers.add(
              nodes[children.singleWhere((c) => c['role'] == 'receiver')['node']
                  as int],
            );
          }
          pending.addAll(children.map((c) => nodes[c['node'] as int]));
        }
        expect(receivers, hasLength(1));
        final receiver = receivers.single;
        expect(
          symbols[receiver['erasedTypeId']]!['name'],
          name == 'other' ? 'OtherStore' : 'Store',
        );
        expect(
          symbols[receiver['typeId']]!['kind'],
          name == 'bounded' ? 'TYPE_PARAMETER' : 'EXTENSION_TYPE',
        );
        expect(receiver['erasedTypeId'], isNot(receiver['typeId']));
      }
      final wrappedLoops = nodes
          .where(
            (n) =>
                n['kind'] == 'ForEachParts' &&
                symbols[n['iteratorTypeId']]?['name'] == 'CursorView',
          )
          .toList();
      expect(wrappedLoops, hasLength(2));
      for (final loop in wrappedLoops) {
        expect(symbols[loop['iteratorErasedTypeId']]!['name'], 'Cursor');
        expect(loop['iteratorTypeId'], isNot(loop['iteratorErasedTypeId']));
      }
      final current = wrappedLoops.singleWhere(
        (n) => symbols[n['currentTypeId']]?['name'] == 'GenericView',
      );
      expect(symbols[current['currentErasedTypeId']]!['name'], 'Store');
      expect(current['currentTypeId'], isNot(current['currentErasedTypeId']));
    },
  );

  test(
    'generic iteration resolves bounds and instantiated current types',
    () async {
      for (final name in [
        'generic_iteration.dart',
        'generic_iteration_pattern.dart',
        'synchronous_iteration.dart',
      ]) {
        write(
          name,
          File('../src/test/resources/semantics/$name').readAsStringSync(),
        );
      }
      final unit = units(
        await export(input: p.join(project.path, 'generic_iteration.dart')),
      ).single;
      expect(unit['status'], 'resolved');
      final symbols = {
        for (final value in entries(unit, 'symbols')) value['id']: value,
      };
      final loops = entries(
        unit,
        'nodes',
      ).where((node) => node['kind'] == 'ForEachParts').toList();
      final patternUnit = units(
        await export(
          input: p.join(project.path, 'generic_iteration_pattern.dart'),
        ),
      ).single;
      expect(patternUnit['status'], 'resolved');
      symbols.addEntries(
        entries(
          patternUnit,
          'symbols',
        ).map((value) => MapEntry(value['id'], value)),
      );
      loops.addAll(
        entries(
          patternUnit,
          'nodes',
        ).where((node) => node['kind'] == 'ForEachParts'),
      );
      expect(loops, hasLength(11));
      for (final loop in loops) {
        for (final (key, name) in [
          ('iteratorTarget', 'iterator'),
          ('moveNextTarget', 'moveNext'),
          ('currentTarget', 'current'),
        ]) {
          expect(symbols[loop[key]]?['name'], name, reason: '$key: $loop');
        }
      }
      expect(loops[0]['iteratorType'], 'Cursor<String>');
      expect(loops[1]['iteratorType'], 'Cursor<E>');
      expect(loops[3]['iteratorType'], 'Cursor<T>');
      expect(loops[6]['iteratorType'], 'Iterator<String>');
      for (final index in [0, 4, 5, 6]) {
        expect(loops[index]['currentType'], 'String');
        expect(symbols[loops[index]['currentTypeId']]?['name'], 'String');
      }
      for (final index in [1, 2, 3]) {
        final type = symbols[loops[index]['currentTypeId']]!;
        expect(type['kind'], 'TYPE_PARAMETER');
        expect(type['name'], index == 3 ? 'T' : 'E');
        final declaration = symbols[loops[index]['currentTarget']]!;
        expect(
          loops[index]['currentTypeId'],
          isNot(declaration['returnTypeId']),
        );
      }
      expect(loops[10]['currentType'], '(String, String)');
      expect(loops[10]['currentTypeId'], '(String, String)');
      expect(loops[0]['currentTarget'], loops[10]['currentTarget']);
      expect(symbols[loops[0]['currentTarget']]!['returnType'], 'T');
      for (final (index, name) in [(7, 'I'), (8, 'J')]) {
        expect(loops[index]['iteratorType'], name);
        final type = symbols[loops[index]['iteratorTypeId']]!;
        expect(type['kind'], 'TYPE_PARAMETER');
        expect(type['name'], name);
        expect(
          symbols[type['owner']]!['name'],
          index == 7 ? 'iteratorBound' : 'chainedIteratorBound',
        );
        expect(symbols[loops[index]['currentTypeId']]!['owner'], type['owner']);
        final declaration = symbols[loops[index]['iteratorTarget']]!;
        expect(declaration['returnType'], 'I');
        expect(
          loops[index]['iteratorTypeId'],
          isNot(declaration['returnTypeId']),
        );
        expect(loops[index]['currentType'], 'E');
        expect(
          symbols[loops[index]['currentTypeId']]!['kind'],
          'TYPE_PARAMETER',
        );
        expect(
          loops[index]['currentTypeId'],
          isNot(symbols[loops[index]['currentTarget']]!['returnTypeId']),
        );
      }
      expect(loops[9]['iteratorType'], 'Cursor<String>');
      expect(loops[9]['currentType'], 'String');
    },
  );

  test(
    'generic iteration preserves invalid and unresolved boundaries',
    () async {
      write(
        'synchronous_iteration.dart',
        File(
          '../src/test/resources/semantics/synchronous_iteration.dart',
        ).readAsStringSync(),
      );
      write('invalid_iteration.dart', """
import 'synchronous_iteration.dart';
void unbounded<T>(T values) { for (final value in values) {} }
void nullable<T extends Values<String>?>(T values) { for (final value in values) {} }
void dynamicLoop(dynamic values) { for (final value in values) {} }
void nullableParameter<T extends Iterable<String>>(T? values) {
  for (final value in values) {}
}
class NullableReturn<E, I extends Cursor<E>> extends Iterable<E> {
  @override
  I? get iterator => null;
}
void nullableReturn<E, I extends Cursor<E>>(NullableReturn<E, I> values) {
  for (final value in values) {}
}
class NullableIterator<E, I extends Cursor<E>?> extends Iterable<E> {
  @override
  I get iterator => throw StateError('invalid');
}
class UnboundedIterator<E, I> extends Iterable<E> {
  @override
  I get iterator => throw StateError('invalid');
}
class DynamicIterator<E> extends Iterable<E> {
  @override
  dynamic get iterator => throw StateError('unknown');
}
void nullableIterator<E, I extends Cursor<E>?>(NullableIterator<E, I> values) {
  for (final value in values) {}
}
void unboundedIterator<E, I>(UnboundedIterator<E, I> values) {
  for (final value in values) {}
}
void dynamicIterator<E>(DynamicIterator<E> values) {
  for (final value in values) {}
}
Future<void> asynchronous<T extends Stream<String>>(T values) async {
  await for (final value in values) {}
}
""");
      final unit = units(
        await export(input: p.join(project.path, 'invalid_iteration.dart')),
      ).single;
      expect(unit['status'], 'partial');
      expect(
        entries(unit, 'diagnostics')
            .where((entry) => entry['severity'] == 'ERROR')
            .map((entry) => (entry['code'] as String).toLowerCase()),
        unorderedEquals([
          'unchecked_use_of_nullable_value',
          'unchecked_use_of_nullable_value',
          'for_in_of_invalid_type',
          'unchecked_use_of_nullable_value',
          'invalid_override',
          'invalid_override',
          'invalid_override',
          'invalid_override',
        ]),
      );
      final loops = entries(
        unit,
        'nodes',
      ).where((node) => node['kind'] == 'ForEachParts').toList();
      expect(loops, hasLength(9));
      for (final loop in loops) {
        for (final key in [
          'iteratorTarget',
          'moveNextTarget',
          'currentTarget',
          'currentTypeId',
        ]) {
          expect(loop.containsKey(key), isFalse, reason: '$key: $loop');
        }
      }
    },
  );

  test(
    'virtual implementation facts preserve covariant and generic declarations',
    () async {
      write(
        'main.dart',
        File(
          '../src/test/resources/semantics/virtual_overrides.dart',
        ).readAsStringSync(),
      );
      final unit = units(await export()).single;
      expect(unit['status'], 'resolved');
      final symbols = {
        for (final value in entries(unit, 'symbols')) value['id']: value,
      };
      final nodes = entries(unit, 'nodes');
      final surface = nodes.singleWhere(
        (node) =>
            node['kind'] == 'ClassDeclaration' && node['name'] == 'Surface',
      )['declaration'];
      final concrete = nodes.singleWhere(
        (node) =>
            node['kind'] == 'ClassDeclaration' && node['name'] == 'Concrete',
      )['declaration'];
      final layer = nodes.singleWhere(
        (node) => node['kind'] == 'MixinDeclaration' && node['name'] == 'Layer',
      )['declaration'];
      final mixed = nodes.singleWhere(
        (node) => node['kind'] == 'ClassDeclaration' && node['name'] == 'Mixed',
      );
      final targets = (mixed['virtualTargets'] as List).cast<Map>();
      for (final origin in [surface, concrete]) {
        final member = symbols.values.singleWhere(
          (s) =>
              s['owner'] == origin &&
              s['kind'] == 'METHOD' &&
              s['name'] == 'echo',
        );
        final target = targets.singleWhere(
          (fact) => fact['member'] == member['id'],
        );
        expect(symbols[target['implementation']]!['owner'], layer);
      }
      for (final (owner, returnType) in [
        (surface, 'T'),
        (concrete, 'U'),
        (layer, 'V'),
      ]) {
        final method = symbols.values.singleWhere(
          (s) =>
              s['owner'] == owner &&
              s['kind'] == 'METHOD' &&
              s['name'] == 'generic',
        );
        expect(method['returnType'], returnType);
        expect(method['abstract'] == true, owner == surface);
      }
    },
  );

  test(
    'mixin super targets retain order and private library identities',
    () async {
      write('base.dart', """
class Base {
  String echo(String value, String ignored) => value;
  String _hidden(String value) => value;
}
mixin Probe on Base {
  String invoke(String value, String ignored) => super.echo(value, ignored);
  String hidden(String value) => super._hidden(value);
}
""");
      write('main.dart', """
import 'base.dart';
mixin Before {
  String echo(String value, String ignored) => value;
  String _hidden(String value) => 'unrelated';
}
class Applied extends Base with Before, Probe {}
class Named = Base with Before, Probe;
class End extends Applied {
  String echo(String value, String ignored) => ignored;
}
""");
      final exported = units(await export());
      expect(exported.every((unit) => unit['status'] == 'resolved'), isTrue);
      final symbols = <String, Map<String, Object?>>{
        for (final unit in exported)
          for (final value in entries(unit, 'symbols'))
            value['id'] as String: value,
      };
      final probe = symbols.values.singleWhere(
        (s) => s['kind'] == 'MIXIN' && s['name'] == 'Probe',
      )['id'];
      for (final name in ['Applied', 'Named']) {
        final application = symbols.values.singleWhere(
          (s) => s['kind'] == 'CLASS' && s['name'] == name,
        );
        final targets = (application['mixinSuperTargets'] as List).cast<Map>();
        final echo = targets.singleWhere(
          (target) => target['mixin'] == probe && target['name'] == 'echo',
        );
        final hidden = targets.singleWhere(
          (target) => target['mixin'] == probe && target['name'] == '_hidden',
        );
        expect(symbols[symbols[echo['target']]!['owner']]!['name'], 'Before');
        expect(symbols[symbols[hidden['target']]!['owner']]!['name'], 'Base');
      }
      final sites = exported
          .expand((unit) => entries(unit, 'nodes'))
          .where((node) => node.containsKey('mixinSuper'))
          .toList();
      expect(sites.length, 2);
      expect(
        sites.every(
          (site) =>
              site['kind'] == 'MethodInvocation' && site['mixinSuper'] == probe,
        ),
        isTrue,
      );
    },
  );

  test(
    'export catch filters separately from exception and stack bindings',
    () async {
      write('main.dart', """
void choose(Object input) {
  try { throw input; }
  on FormatException catch (error, stack) { print(error); print(stack); }
  catch (error) { print(error); }
}
""");
      final unit = units(await export()).single;
      expect(unit['status'], 'resolved');
      final nodes = entries(unit, 'nodes');
      final clauses = nodes
          .where((node) => node['kind'] == 'CatchClause')
          .toList();
      final typed = (clauses.first['children'] as List).cast<Map>();
      final filter =
          nodes[typed.singleWhere((child) => child['role'] == 'type')['node']
              as int];
      expect(filter['kind'], 'NamedType');
      expect(filter['typeId'], contains('FormatException'));
      final bindings = typed
          .where((child) => ['exception', 'stack'].contains(child['role']))
          .map((child) => nodes[child['node'] as int])
          .toList();
      expect(bindings.map((binding) => binding['name']), ['error', 'stack']);
      expect(
        bindings.map((binding) => binding['declaration']).toSet().length,
        2,
      );
      expect(
        (clauses.last['children'] as List).cast<Map>().where(
          (child) => child['role'] == 'type',
        ),
        isEmpty,
      );
    },
  );

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
    'export comparison constants and extension argument identities',
    () async {
      write(
        'comparison.dart',
        File(
          '../src/test/resources/semantics/comparison_cache.dart',
        ).readAsStringSync(),
      );
      write(
        'extension.dart',
        File(
          '../src/test/resources/semantics/extension_cache.dart',
        ).readAsStringSync(),
      );
      final exported = units(await export());
      expect(exported.map((unit) => unit['status']), everyElement('resolved'));
      final comparisons =
          entries(
            exported.singleWhere((unit) => unit['file'] == 'comparison.dart'),
            'nodes',
          ).where(
            (node) =>
                ['ConstantPattern', 'RelationalPattern'].contains(node['kind']),
          );
      expect(
        comparisons.map((node) => node['constantIdentity']).toSet(),
        hasLength(2),
      );
      expect(
        comparisons.map((node) => node['constantIdentity']),
        everyElement(isNotNull),
      );
      final invocations =
          entries(
                exported.singleWhere(
                  (unit) => unit['file'] == 'extension.dart',
                ),
                'nodes',
              )
              .where(
                (node) => [
                  'PatternField',
                  'RelationalPattern',
                ].contains(node['kind']),
              )
              .toList();
      expect(
        invocations.map((node) => node['extensionTarget']).toSet(),
        hasLength(1),
      );
      expect(
        invocations.map((node) => node['extensionTarget']),
        everyElement(isNotNull),
      );
      final types = <String, Set<Object?>>{};
      for (final invocation in invocations) {
        final arguments = invocation['extensionTypeArguments'] as List;
        final identities = invocation['extensionArgumentIdentities'] as List;
        expect(arguments, hasLength(1));
        expect(identities, hasLength(1));
        types
            .putIfAbsent(arguments.single as String, () => {})
            .add(identities.single);
      }
      expect(types.keys, unorderedEquals(['int', 'num']));
      expect(types['int'], hasLength(1));
      expect(types['num'], hasLength(1));
      expect(types['int'], isNot(types['num']));
    },
  );

  test('export distinct anonymous generic function identities', () async {
    write(
      'main.dart',
      File(
        '../src/test/resources/semantics/generic_function_scopes.dart',
      ).readAsStringSync(),
    );
    final unit = units(await export()).single;
    expect(unit['status'], 'resolved');
    final signatures = entries(unit, 'nodes').where(
      (node) =>
          [
            'GenericFunctionType',
            'FunctionTypedFormalParameter',
          ].contains(node['kind']) &&
          (node['children'] as List).any(
            (child) => (child as Map)['role'] == 'typeParameters',
          ),
    );
    expect(signatures, hasLength(7));
    final identities = signatures
        .map((node) => node['typeDeclaration'])
        .toSet();
    expect(identities, hasLength(7));
    expect(identities, isNot(contains(null)));
    expect(identities.every((id) => !(id as String).contains('#-1:')), isTrue);
    final owners = entries(unit, 'symbols')
        .where((symbol) => symbol['kind'] == 'TYPE_PARAMETER')
        .map((symbol) => symbol['owner'])
        .toSet();
    expect(owners, hasLength(10));
    expect(
      owners.every((owner) => !(owner as String).contains('#-1:')),
      isTrue,
    );
  });

  test('export scoped generic declarations and bounds', () async {
    write(
      'main.dart',
      File(
        '../src/test/resources/semantics/generic_scopes.dart',
      ).readAsStringSync(),
    );
    final unit = units(await export()).single;
    expect(unit['status'], 'resolved');
    final declarations = entries(unit, 'nodes')
        .where((node) => node['kind'] == 'TypeParameter')
        .map((node) => node['declaration'])
        .toSet();
    expect(declarations, hasLength(13));
    expect(declarations, isNot(contains(null)));
    final symbols = {
      for (final symbol in entries(unit, 'symbols')) symbol['id']: symbol,
    };
    for (final id in declarations) {
      final parameter = symbols[id]!;
      expect(parameter['kind'], 'TYPE_PARAMETER');
      expect(parameter['owner'], isNotNull);
      expect(parameter['boundTypeId'], isNotNull);
      if (parameter['boundType'] == 'T' || parameter['boundType'] == 'U') {
        expect(declarations, contains(parameter['boundTypeId']));
      }
    }
    expect(
      declarations.map((id) => symbols[id]!['boundType']),
      containsAll(['Data', 'num', 'Object?', 'Comparable<T>', 'T', 'U']),
    );
    final typed = symbols.values.where(
      (symbol) => symbol['kind'] == 'PARAMETER' && symbol['type'] == 'T',
    );
    expect(typed, isNotEmpty);
    expect(
      typed.map((symbol) => symbol['typeId']),
      everyElement(isIn(declarations)),
    );
    expect(
      symbols.values.singleWhere(
        (symbol) => symbol['name'] == 'optional',
      )['genericSignature'],
      contains('T?'),
    );
  });

  test(
    'distinguish extension arguments with identical display names',
    () async {
      write('a.dart', 'class Token {}');
      write('b.dart', 'class Token {}');
      write('main.dart', '''
import 'a.dart' as a;
import 'b.dart' as b;
class Both implements a.Token, b.Token {}
class Box<T> {}
extension View<T> on Box<T> { String get tag => '\$T'; }
String classify(Box<Both> input) => switch (input) {
  Box<a.Token>(tag: 'missing') => 'wrong',
  Box<b.Token>(tag: var tag) => tag,
};
''');
      final exported = units(await export());
      expect(exported.map((unit) => unit['status']), everyElement('resolved'));
      final fields = entries(
        exported.singleWhere((unit) => unit['file'] == 'main.dart'),
        'nodes',
      ).where((node) => node['kind'] == 'PatternField');
      expect(fields, hasLength(2));
      expect(
        fields.map((node) => node['extensionTarget']).toSet(),
        hasLength(1),
      );
      expect(
        fields
            .map((node) => (node['extensionTypeArguments'] as List).single)
            .toSet(),
        hasLength(1),
      );
      expect(
        fields
            .map((node) => (node['extensionArgumentIdentities'] as List).single)
            .toSet(),
        hasLength(2),
      );
    },
  );

  test('export map pattern targets and constant key identities', () async {
    write(
      'main.dart',
      File(
        '../src/test/resources/semantics/map_patterns.dart',
      ).readAsStringSync(),
    );
    final unit = units(await export()).single;
    expect(unit['status'], 'resolved');
    final symbols = {
      for (final symbol in entries(unit, 'symbols')) symbol['id']: symbol,
    };
    final nodes = entries(unit, 'nodes');
    final owners = <Object?>{};
    for (final pattern in nodes.where((node) => node['kind'] == 'MapPattern')) {
      expect(pattern['requiredType'], startsWith('Map<'));
      expect(pattern['valueType'], isNotNull);
      for (final entry in {
        'indexTarget': '[]',
        'containsKeyTarget': 'containsKey',
      }.entries) {
        final target = symbols[pattern[entry.key]];
        expect(target, isNotNull, reason: entry.key);
        expect(target!['name'], entry.value);
        owners.add(symbols[target['owner']]!['name']);
      }
    }
    expect(owners, containsAll(['LoggedMap', 'ScalarMap', 'Map']));
    expect(
      nodes
          .where((node) => node['kind'] == 'MapPattern')
          .map((node) => node['valueType']),
      containsAll(['Object?', 'int', 'T', 'String']),
    );
    final keys = <String, Set<Object?>>{};
    for (final entry in nodes.where(
      (node) => node['kind'] == 'MapPatternEntry',
    )) {
      final key = (entry['children'] as List).cast<Map>().singleWhere(
        (child) => child['role'] == 'key',
      );
      final node = nodes[key['node'] as int];
      final offset = node['offset'] as int;
      final code = (unit['source'] as String).substring(
        offset,
        offset + (node['length'] as int),
      );
      expect(entry['keyIdentity'], isNotNull);
      keys.putIfAbsent(code, () => {}).add(entry['keyIdentity']);
    }
    expect(keys['selectedKey'], hasLength(1));
    expect(keys['selectedAlias'], keys['selectedKey']);
    expect(keys["'other'"], isNot(keys['selectedKey']));
    expect(keys['null'], hasLength(1));
    expect(keys['null'], isNot(keys['selectedKey']));
  });

  test('export list pattern type and member targets', () async {
    write(
      'main.dart',
      File(
        '../src/test/resources/semantics/list_patterns.dart',
      ).readAsStringSync(),
    );
    final unit = units(await export()).single;
    expect(unit['status'], 'resolved');
    final symbols = {
      for (final symbol in entries(unit, 'symbols')) symbol['id']: symbol,
    };
    final patterns = entries(
      unit,
      'nodes',
    ).where((node) => node['kind'] == 'ListPattern').toList();
    expect(patterns, isNotEmpty);
    for (final pattern in patterns) {
      expect(pattern['requiredType'], 'List<Object?>');
      for (final entry in {
        'lengthTarget': 'length',
        'indexTarget': '[]',
        'sublistTarget': 'sublist',
      }.entries) {
        final target = symbols[pattern[entry.key]];
        expect(target, isNotNull, reason: entry.key);
        expect(target!['name'], entry.value);
        expect(symbols[target['owner']]!['name'], 'LoggedList');
      }
    }
  });

  test(
    'export comparison targets for constant and relational patterns',
    () async {
      write(
        'main.dart',
        File(
          '../src/test/resources/semantics/pattern_operators.dart',
        ).readAsStringSync(),
      );
      final unit = units(await export()).single;
      expect(unit['status'], 'resolved');
      final nodes = entries(unit, 'nodes');
      final symbols = {
        for (final symbol in entries(unit, 'symbols')) symbol['id']: symbol,
      };
      String code(Map<String, Object?> node) =>
          (unit['source'] as String).substring(
            node['offset'] as int,
            (node['offset'] as int) + (node['length'] as int),
          );
      final constant = nodes.singleWhere(
        (node) => node['kind'] == 'ConstantPattern' && code(node) == 'marker',
      );
      expect(constant['operatorTarget'], isNotNull);
      final equality = symbols[constant['operatorTarget']]!;
      expect(equality['name'], '==');
      expect(symbols[equality['owner']]!['name'], 'Comparison');
      final relational = nodes
          .where(
            (node) =>
                node['kind'] == 'RelationalPattern' && code(node) != '== null',
          )
          .toList();
      expect(relational.map((node) => node['operator']), ['==', '!=', '>']);
      expect(
        relational.map((node) => symbols[node['operatorTarget']]!['name']),
        ['==', '==', '>'],
      );
      expect(
        relational.map(
          (node) => symbols[symbols[node['operatorTarget']]!['owner']]!['name'],
        ),
        everyElement('Comparison'),
      );
    },
  );

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
      final enumeration = nodes.singleWhere(
        (n) => n['kind'] == 'EnumDeclaration',
      );
      final constants = nodes.where(
        (n) => n['kind'] == 'EnumConstantDeclaration',
      );
      expect(constants.map((n) => n['ordinal']), [0, 1]);
      expect(
        constants.map((n) => n['typeId']),
        everyElement(enumeration['declaration']),
      );
      final values = entries(
        unit,
        'symbols',
      ).singleWhere((s) => s['id'] == enumeration['values']);
      expect(values['name'], 'values');
      expect(values['owner'], enumeration['declaration']);
      expect(values['static'], isTrue);
      expect(values['const'], isTrue);
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
