import 'package:test/test.dart';

import '../../src/test/resources/semantics/map_patterns.dart' as fixture;

void main() {
  test('map patterns distinguish absent keys and cache nullable reads', () {
    for (final present in [false, true]) {
      for (final value in [null, 'input', 1]) {
        fixture.LoggedMap input({bool changing = false}) => fixture.LoggedMap({
          if (present) fixture.selectedKey: value,
        }, changeAfterRead: changing);
        final calls = [
          'index:selected',
          if (value == null || !present) 'contains:selected',
        ];
        fixture.trace.clear();
        expect(fixture.wildcard(input()), present);
        expect(fixture.trace, calls);
        fixture.trace.clear();
        expect(fixture.typedWildcard(input()), present && value is int);
        expect(fixture.trace, calls);
        fixture.trace.clear();
        expect(
          fixture.cases(input(changing: true)),
          present ? value : 'absent',
        );
        expect(fixture.trace, calls);
        for (final accept in [false, true]) {
          fixture.trace.clear();
          expect(
            fixture.guarded(input(changing: true), accept),
            present ? value : 'absent',
          );
          expect(fixture.trace, calls);
        }
        fixture.trace.clear();
        expect(fixture.multiple(input()), false);
        expect(fixture.trace, [
          ...calls,
          if (present) ...['index:other', 'contains:other'],
        ]);
        fixture.trace.clear();
        expect(
          fixture.separate(input(changing: true)),
          present ? [value, 'changed'] : ['absent', 'absent'],
        );
        expect(fixture.trace, [
          ...calls,
          'index:selected',
          if (!present) 'contains:selected',
        ]);
      }
    }
    for (final present in [false, true]) {
      fixture.trace.clear();
      expect(
        fixture.generic(fixture.TypedMap({if (present) 'selected': 1})),
        present,
      );
      expect(fixture.trace, ['index:selected']);
      fixture.trace.clear();
      expect(
        fixture.generic<String?>(
          fixture.LoggedMap({
            if (present) 'selected': null,
          }).cast<String, String?>(),
        ),
        present,
      );
      expect(fixture.trace, ['index:selected', 'contains:selected']);
    }
    for (final value in [null, 'input', 1]) {
      fixture.trace.clear();
      expect(
        fixture.nullKey(
          fixture.LoggedMap({null: value}, changeAfterRead: true),
        ),
        value,
      );
      expect(fixture.trace, ['index:null', if (value == null) 'contains:null']);
    }
    fixture.trace.clear();
    expect(
      fixture.nested(
        fixture.LoggedMap({
          'left': fixture.LoggedMap({'selected': 'input'}),
          'right': fixture.LoggedMap({'selected': 'other'}),
        }),
      ),
      'other',
    );
    expect(fixture.trace, [
      'index:left',
      'index:selected',
      'index:right',
      'index:selected',
    ]);
    for (final state in [false, true]) {
      fixture.trace.clear();
      expect(
        fixture.nestedMember(
          fixture.LoggedMap({'selected': fixture.EntryState(state)}),
        ),
        state,
      );
      expect(fixture.trace, ['index:selected', 'member']);
    }
    expect(fixture.selected('input'), 'input');
    expect(fixture.independent('input'), 'other');
    expect(fixture.constant('input'), 'constant');
    fixture.trace.clear();
    expect(fixture.typedMap(fixture.LoggedMap({'selected': 1})), false);
    expect(fixture.trace, isEmpty);
    expect(fixture.typedMap(<String, int>{'selected': 1}), true);
    expect(fixture.typedMap(null), false);
    expect(fixture.typedMap('input'), false);
  });
}
