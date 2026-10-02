import 'dart:io';

import 'package:test/test.dart';

import '../../src/test/resources/semantics/map_pattern_results.dart'
    as patterns;

void main() {
  test(
    'pinned compiler reports the extension map operator disagreement',
    () async {
      final scratch = Directory('../../../../agents')
        ..createSync(recursive: true);
      final output = scratch.createTempSync('dart-map-pattern-');
      try {
        final compilation = await Process.run(Platform.resolvedExecutable, [
          'compile',
          'kernel',
          '../src/test/resources/semantics/map_pattern_extension_diagnostic.dart',
          '-o',
          '${output.path}/oracle.dill',
        ]);
        expect(compilation.exitCode, isNot(0));
        expect(compilation.stderr, contains('ObjectAccessTarget.classMember'));
        expect(compilation.stderr, contains('visitMapPattern'));
        expect(File('${output.path}/oracle.dill').existsSync(), isFalse);
      } finally {
        output.deleteSync(recursive: true);
      }
    },
  );

  test('map index results retain instantiated call and child views', () {
    for (final first in ['value', 'independent']) {
      patterns.trace.clear();
      expect(patterns.selected(patterns.Entries({'selected': first})), first);
      expect(patterns.trace, ['index:selected']);
      patterns.trace.clear();
      expect(patterns.broad(patterns.Narrow({'selected': first})), first);
      expect(patterns.trace, ['index:selected']);
      patterns.trace.clear();
      expect(patterns.typed(patterns.Entries({'selected': first})), first);
      expect(patterns.trace, ['index:selected']);
      patterns.trace.clear();
      expect(patterns.typed(patterns.Entries({'selected': 123})), 'other');
      expect(patterns.trace, ['index:selected']);
      patterns.trace.clear();
      expect(patterns.generic(patterns.Entries({'selected': first})), first);
      expect(patterns.trace, ['index:selected']);
    }
  });

  test('map presence guards distinguish nullable and missing results', () {
    patterns.trace.clear();
    expect(patterns.selected(patterns.Entries({})), 'absent');
    expect(patterns.trace, ['index:selected']);
    patterns.trace.clear();
    expect(patterns.nullable(patterns.Entries({'selected': null})), isNull);
    expect(patterns.trace, ['index:selected', 'contains:selected']);
    patterns.trace.clear();
    expect(patterns.nullable(patterns.Entries({})), 'absent');
    expect(patterns.trace, ['index:selected', 'contains:selected']);
    patterns.trace.clear();
    expect(patterns.selected(patterns.Entries({}, 'index-only')), 'index-only');
    expect(patterns.trace, ['index:selected']);
  });

  test('map cached reads retain each required result view', () {
    for (final first in ['value', 'independent']) {
      for (final accept in [false, true]) {
        patterns.trace.clear();
        expect(
          patterns.cached(
            patterns.Entries<String, String?>({'selected': first}),
            accept,
          ),
          first,
        );
        expect(patterns.trace, ['index:selected']);
        patterns.trace.clear();
        expect(
          patterns.cachedWrapper(
            patterns.Entries({
              'selected': patterns.View(patterns.Store(first)),
            }),
            accept,
          ),
          first,
        );
        expect(patterns.trace, ['index:selected', 'store']);
      }
    }
  });

  test('map results retain inner and outer wrapper views', () {
    for (final first in ['value', 'independent']) {
      final entries = patterns.Entries({
        'selected': patterns.View(patterns.Store(first)),
      });
      patterns.trace.clear();
      expect(patterns.wrapped(entries), first);
      expect(patterns.trace, ['index:selected', 'store']);
      patterns.trace.clear();
      expect(patterns.outerWrapper(patterns.MapView(entries)), first);
      expect(patterns.trace, ['index:selected', 'store']);
    }
  });

  test('map presence tests use nullable wrapper representation types', () {
    patterns.trace.clear();
    expect(
      patterns.nullableWrapper(
        patterns.Entries({'selected': patterns.NullableView<String?>(null)}),
      ),
      isNull,
    );
    expect(patterns.trace, ['index:selected', 'contains:selected']);
    patterns.trace.clear();
    expect(patterns.nullableWrapper(patterns.Entries({})), 'absent');
    expect(patterns.trace, ['index:selected', 'contains:selected']);
  });
}
