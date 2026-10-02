import 'map_pattern_results.dart';

extension type OwnMapView(Entries<String, Object?> representation)
    implements Map<String, Object?> {
  String? operator [](Object? key) => 'static-index';
}

Object? selected(OwnMapView input) => switch (input) {
  {'selected': var value} => value,
  _ => 'absent',
};

void main() {
  print(selected(OwnMapView(Entries({'selected': 123}))));
}
