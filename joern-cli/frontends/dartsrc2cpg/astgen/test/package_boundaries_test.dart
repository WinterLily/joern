import 'dart:io';

import 'package:test/test.dart';

Future<void> runPinned(String packageName, String program) async {
  final scratch = Directory('../../../../agents').absolute;
  final package = Directory('${scratch.path}/dart-corpus/$packageName');
  final configuration = File('${package.path}/.dart_tool/package_config.json');
  expect(
    configuration.existsSync(),
    isTrue,
    reason: 'Prepare the pinned Dart corpus first',
  );
  final output = scratch.createTempSync('dart-corpus-capture-');
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
  final skip = Platform.environment['DART_CORPUS_TESTS'] != '1'
      ? 'Set DART_CORPUS_TESTS=1 after preparing the pinned corpus'
      : false;
  test(
    'pinned args-2.7.0 value and isolation boundaries',
    () => runPinned('args-2.7.0', r'''
import 'package:args/args.dart';
import 'package:args/src/arg_results.dart';
import 'package:args/src/option.dart';
void main() {
  final parser = ArgParser()..addOption('value', defaultsTo: 'fallback');
  final option = parser.options['value']!;
  for (final input in ['', 'first', 'second']) {
    if (option.valueOrDefault(input) != input || option.getOrDefault(input) != input) {
      throw StateError('Lost supplied value or deprecated forwarding');
    }
    final results = newArgResults(parser, {'value': input}, 'command', null, [], []);
    if (results.name != 'command' || results.option('value') != input) {
      throw StateError('Mixed name and parsed constructor slots');
    }
    final renamed = newArgResults(parser, {'value': input}, 'other', null, [], []);
    if (renamed.name != 'other' || renamed.option('value') != input) {
      throw StateError('Mixed independent constructor invocations');
    }
  }
  if (option.valueOrDefault(null) != 'fallback' || option.getOrDefault(null) != 'fallback') {
    throw StateError('Lost null fallback');
  }
  final multiple = newOption('values', null, null, null, null, null, null, null, OptionType.multiple);
  final first = multiple.valueOrDefault(null) as List<String>;
  final second = multiple.valueOrDefault(null) as List<String>;
  first.add('first');
  if (second.isNotEmpty) throw StateError('Shared fresh multi-option defaults');
}
'''),
    skip: skip,
  );
  test(
    'pinned collection-1.19.1 value and isolation boundaries',
    () => runPinned('collection-1.19.1', r'''
import 'package:collection/collection.dart';
import 'package:collection/src/utils.dart';
import 'package:collection/src/algorithms.dart';
void main() {
  final object = Object();
  if (!identical(identity(object), object)) throw StateError('Lost identity');
  for (final list in <List<int>>[[], [1], [1, 3, 5]]) {
    for (final value in [0, 1, 2, 3, 5, 6]) {
      final visits = <int>[];
      final result = binarySearchBy<int, int>(list, (element) {
        visits.add(element);
        return element;
      }, (a, b) => a.compareTo(b), value);
      if (visits.first != value || result != list.indexOf(value) ||
          binarySearch(list, value) != result) {
        throw StateError('Mixed key input, sorted list or not-found result');
      }
    }
  }
  for (final original in <List<int>>[[], [1], [1, 2], [1, 2, 3, 4, 5]]) {
    final list = List<int>.of(original);
    reverse(list);
    if (!const ListEquality<int>().equals(list, original.reversed.toList())) {
      throw StateError('Lost swap temporary or loop boundaries');
    }
  }
  final partial = [0, 1, 2, 3, 4];
  reverse(partial, 1, 4);
  if (!const ListEquality<int>().equals(partial, [0, 3, 2, 1, 4])) {
    throw StateError('Lost reverse range isolation');
  }
}
'''),
    skip: skip,
  );
  test(
    'pinned meta-1.17.0 value and isolation boundaries',
    () => runPinned('meta-1.17.0', r'''
import 'package:meta/meta.dart';
import 'package:meta/meta_meta.dart';
void main() {
  for (final reason in ['', 'first', 'second']) {
    for (final parameter in ['left', 'right']) {
      final value = UseResult.unless(reason: reason, parameterDefined: parameter);
      if (value.reason != reason || value.parameterDefined != parameter) {
        throw StateError('Mixed named field formals');
      }
    }
    final value = UseResult(reason);
    if (value.reason != reason || value.parameterDefined != null) {
      throw StateError('Mixed reason with constant null field');
    }
  }
  if (TargetKind.parameter.name != 'parameter' ||
      TargetKind.parameter.displayString != 'parameters' ||
      TargetKind.setter.name != 'setter' || TargetKind.setter.displayString != 'setters') {
    throw StateError('Mixed positional fields in public target constants');
  }
}
'''),
    skip: skip,
  );
}
