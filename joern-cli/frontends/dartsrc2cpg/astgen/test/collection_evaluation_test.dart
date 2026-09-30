import 'package:test/test.dart';

void main() {
  test(
    'null-aware entries evaluate a key once and skip its value for null',
    () {
      for (final key in <String?>[null, 'key']) {
        for (final value in <String?>[null, 'value']) {
          final trace = <String>[];
          String? read(String name, String? value) {
            trace.add(name);
            return value;
          }

          final map = {?read('key', key): ?read('value', value)};
          expect(trace, key == null ? ['key'] : ['key', 'value']);
          expect(map, key == null || value == null ? {} : {key: value});
          trace.clear();
          final list = [
            for (final item in [key, value]) ?read('item', item),
          ];
          expect(trace, ['item', 'item']);
          expect(list, [if (key != null) key, if (value != null) value]);
        }
      }
    },
  );
}
