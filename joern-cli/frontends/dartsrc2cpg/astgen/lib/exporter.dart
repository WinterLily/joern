import 'dart:io';

import 'package:analyzer/dart/analysis/analysis_context_collection.dart';
import 'package:analyzer/dart/analysis/results.dart';
import 'package:analyzer/dart/analysis/utilities.dart';
import 'package:analyzer/dart/ast/ast.dart';
import 'package:analyzer/dart/element/element.dart';
import 'package:analyzer/source/line_info.dart';
import 'package:path/path.dart' as p;

const protocolVersion = 1;
const exporterVersion = '0.1.0';
const analyzerVersion = '8.4.1';
const supportedSdkVersion = '3.9.2';

/// The root fixes file identities even when exporting just one package file.
Stream<Map<String, Object?>> exportProject({
  required String root,
  String? input,
  String? sdkPath,
}) async* {
  root = p.normalize(p.absolute(root));
  input = p.normalize(p.absolute(input ?? root));
  if (!Directory(root).existsSync() ||
      !(input == root || p.isWithin(root, input))) {
    throw ArgumentError('Input must be within an existing project root');
  }
  final type = FileSystemEntity.typeSync(input);
  if (type != FileSystemEntityType.file &&
      type != FileSystemEntityType.directory) {
    throw ArgumentError('Input does not exist: $input');
  }
  if (type == FileSystemEntityType.file && !input.endsWith('.dart')) {
    throw ArgumentError('Input file must have a .dart extension');
  }
  sdkPath = p.normalize(
    p.absolute(sdkPath ?? p.dirname(p.dirname(Platform.resolvedExecutable))),
  );
  final sdkVersionFile = File(p.join(sdkPath, 'version'));
  if (!sdkVersionFile.existsSync() ||
      !Directory(p.join(sdkPath, 'lib')).existsSync()) {
    throw ArgumentError('Not a Dart SDK: $sdkPath');
  }
  final sdkVersion = sdkVersionFile.readAsStringSync().trim();
  if (sdkVersion != supportedSdkVersion) {
    throw ArgumentError(
      'Expected Dart SDK $supportedSdkVersion, found $sdkVersion',
    );
  }
  final files = type == FileSystemEntityType.file
      ? [input]
      : Directory(input)
            .listSync(recursive: true, followLinks: false)
            .whereType<File>()
            .map((file) => file.path)
            .where(
              (path) =>
                  path.endsWith('.dart') &&
                  !p
                      .split(p.relative(path, from: root))
                      .any((part) => part == '.dart_tool' || part == '.git'),
            )
            .toList();
  files.sort();
  final collection = AnalysisContextCollection(
    includedPaths: [root],
    sdkPath: sdkPath,
  );
  try {
    yield {
      'record': 'header',
      'protocolVersion': protocolVersion,
      'exporterVersion': exporterVersion,
      'analyzerVersion': analyzerVersion,
      'sdkVersion': sdkVersion,
      'offsetEncoding': 'utf-16',
    };
    for (final file in files) {
      final context = collection.contextFor(file);
      final result = await context.currentSession.getResolvedUnit(file);
      if (result is ResolvedUnitResult) {
        yield _UnitEncoder(root, file, result.content, result.lineInfo).encode(
          result.unit,
          result.diagnostics
              .map(
                (error) => <String, Object?>{
                  'code': error.diagnosticCode.name,
                  'severity': error.diagnosticCode.severity.name,
                  'message': error.message,
                  'offset': error.offset,
                  'length': error.length,
                },
              )
              .toList(),
          library: result.libraryElement.firstFragment.source.fullName,
        );
      } else {
        final parsed = parseString(
          content: File(file).readAsStringSync(),
          path: file,
          throwIfDiagnostics: false,
        );
        yield _UnitEncoder(root, file, parsed.content, parsed.lineInfo).encode(
          parsed.unit,
          [
            {
              'code': 'resolution_unavailable',
              'message': 'Analyzer returned ${result.runtimeType}',
              'severity': 'ERROR',
            },
            ...parsed.errors.map(
              (error) => <String, Object?>{
                'code': error.diagnosticCode.name,
                'severity': error.diagnosticCode.severity.name,
                'message': error.message,
                'offset': error.offset,
                'length': error.length,
              },
            ),
          ],
          resolved: false,
        );
      }
    }
    yield {'record': 'summary', 'files': files.length};
  } finally {
    await collection.dispose();
  }
}

class _UnitEncoder {
  final String root;
  final String file;
  final String source;
  final LineInfo lines;
  final Map<String, Map<String, Object?>> symbols = {};
  final List<Map<String, Object?>> nodes = [];
  final Set<String> unsupported = {};

  _UnitEncoder(this.root, this.file, this.source, this.lines);

  String fileId(String path) => p.isWithin(root, path)
      ? Uri(
          path: p.posix.joinAll(p.split(p.relative(path, from: root))),
        ).toString()
      : Uri.file(path).toString();

  String? symbol(Element? element) {
    if (element == null) return null;
    element = element.baseElement;
    final fragment = element.firstFragment;
    final unit = fragment.libraryFragment;
    final uri = unit?.source.uri;
    final location = uri != null && uri.scheme != 'file'
        ? uri.toString()
        : unit == null
        ? ''
        : fileId(unit.source.fullName);
    final id =
        '$location#${fragment.nameOffset}:${element.kind.name}:${element.name}';
    if (!symbols.containsKey(id)) {
      symbols[id] = {
        'id': id,
        'name': element.name,
        'kind': element.kind.name,
        'file': location,
        'offset': fragment.nameOffset,
        'library': element.library == null
            ? null
            : fileId(element.library!.firstFragment.source.fullName),
        if (element is VariableElement) 'type': element.type.getDisplayString(),
        if (element is ExecutableElement)
          'returnType': element.returnType.getDisplayString(),
        if (element is FormalParameterElement) ...{
          'named': element.isNamed,
          'required': element.isRequired,
          'defaultValue': element.defaultValueCode,
        },
      };
      if (element is ExecutableElement) {
        symbols[id]!['parameters'] = element.formalParameters
            .map(symbol)
            .toList();
      }
    }
    return id;
  }

  Map<String, Object?> encode(
    CompilationUnit unit,
    List<Map<String, Object?>> diagnostics, {
    String? library,
    bool resolved = true,
  }) {
    node(unit);
    return {
      'record': 'unit',
      'file': fileId(file),
      'library': library == null ? null : fileId(library),
      'status': !resolved
          ? 'parsed'
          : diagnostics.any((d) => d['severity'] == 'ERROR')
          ? 'partial'
          : 'resolved',
      'source': source,
      'diagnostics': diagnostics,
      'unsupportedKinds': unsupported.toList()..sort(),
      'nodes': nodes,
      'symbols': (symbols.keys.toList()..sort())
          .map((id) => symbols[id])
          .toList(),
    };
  }

  int node(AstNode ast) {
    final id = nodes.length;
    final location = lines.getLocation(ast.offset);
    final record = <String, Object?>{
      'id': id,
      'offset': ast.offset,
      'length': ast.length,
      'line': location.lineNumber,
      'column': location.columnNumber,
    };
    nodes.add(record);
    final children = <Map<String, Object?>>[];
    void child(String role, AstNode? value) {
      if (value != null) children.add({'role': role, 'node': node(value)});
    }

    void many(String role, Iterable<AstNode> values) {
      for (final value in values) {
        child(role, value);
      }
    }

    String kind;
    switch (ast) {
      case CompilationUnit():
        kind = 'CompilationUnit';
        many('directive', ast.directives);
        many('declaration', ast.declarations);
      case ImportDirective():
        kind = 'ImportDirective';
        child('uri', ast.uri);
        child('prefix', ast.prefix);
        many('configuration', ast.configurations);
        many('combinator', ast.combinators);
      case FunctionDeclaration():
        kind = 'FunctionDeclaration';
        record['name'] = ast.name.lexeme;
        record['declaration'] = symbol(ast.declaredFragment?.element);
        child('returnType', ast.returnType);
        child('function', ast.functionExpression);
      case FunctionExpression():
        kind = 'FunctionExpression';
        child('typeParameters', ast.typeParameters);
        child('parameters', ast.parameters);
        child('body', ast.body);
      case FormalParameterList():
        kind = 'FormalParameterList';
        many('parameter', ast.parameters);
      case DefaultFormalParameter():
        kind = 'DefaultFormalParameter';
        child('parameter', ast.parameter);
        child('defaultValue', ast.defaultValue);
      case SimpleFormalParameter():
        kind = 'SimpleFormalParameter';
        record['name'] = ast.name?.lexeme;
        record['declaration'] = symbol(ast.declaredFragment?.element);
        child('type', ast.type);
      case BlockFunctionBody():
        kind = 'BlockFunctionBody';
        child('block', ast.block);
      case ExpressionFunctionBody():
        kind = 'ExpressionFunctionBody';
        child('expression', ast.expression);
      case Block():
        kind = 'Block';
        many('statement', ast.statements);
      case VariableDeclarationStatement():
        kind = 'VariableDeclarationStatement';
        child('variables', ast.variables);
      case VariableDeclarationList():
        kind = 'VariableDeclarationList';
        child('type', ast.type);
        many('variable', ast.variables);
      case VariableDeclaration():
        kind = 'VariableDeclaration';
        record['name'] = ast.name.lexeme;
        record['declaration'] = symbol(ast.declaredFragment?.element);
        child('initializer', ast.initializer);
      case ExpressionStatement():
        kind = 'ExpressionStatement';
        child('expression', ast.expression);
      case ReturnStatement():
        kind = 'ReturnStatement';
        child('expression', ast.expression);
      case MethodInvocation():
        kind = 'MethodInvocation';
        record['target'] = symbol(ast.methodName.element);
        child('receiver', ast.target);
        child('name', ast.methodName);
        child('arguments', ast.argumentList);
      case ArgumentList():
        kind = 'ArgumentList';
        many('argument', ast.arguments);
        record['bindings'] = ast.arguments
            .map(
              (arg) => {
                'offset': arg.offset,
                'parameter': symbol(arg.correspondingParameter),
              },
            )
            .toList();
      case NamedExpression():
        kind = 'NamedExpression';
        record['name'] = ast.name.label.name;
        child('expression', ast.expression);
      case SimpleIdentifier():
        kind = 'SimpleIdentifier';
        record['name'] = ast.name;
        record['reference'] = symbol(ast.element);
      case NamedType():
        kind = 'NamedType';
        record['name'] = ast.toSource();
        child('typeArguments', ast.typeArguments);
      case TypeArgumentList():
        kind = 'TypeArgumentList';
        many('argument', ast.arguments);
      case IndexExpression():
        kind = 'IndexExpression';
        child('target', ast.target);
        child('index', ast.index);
      case AssignmentExpression():
        kind = 'AssignmentExpression';
        record['operator'] = ast.operator.lexeme;
        child('left', ast.leftHandSide);
        child('right', ast.rightHandSide);
      case BinaryExpression():
        kind = 'BinaryExpression';
        record['operator'] = ast.operator.lexeme;
        child('left', ast.leftOperand);
        child('right', ast.rightOperand);
      case SimpleStringLiteral():
        kind = 'StringLiteral';
        record['value'] = ast.value;
      case IntegerLiteral():
        kind = 'IntegerLiteral';
        record['value'] = ast.value;
      case DoubleLiteral():
        kind = 'DoubleLiteral';
        record['value'] = ast.value;
      case BooleanLiteral():
        kind = 'BooleanLiteral';
        record['value'] = ast.value;
      case NullLiteral():
        kind = 'NullLiteral';
      default:
        kind = 'Unsupported';
        final syntax = ast.runtimeType.toString().replaceFirst(
          RegExp(r'Impl$'),
          '',
        );
        record['syntax'] = syntax;
        unsupported.add(syntax);
        many('child', ast.childEntities.whereType<AstNode>());
    }
    record['kind'] = kind;
    if (ast is FunctionBody) {
      record['async'] = ast.isAsynchronous;
      record['generator'] = ast.isGenerator;
    }
    if (ast is Expression) record['type'] = ast.staticType?.getDisplayString();
    record['children'] = children;
    return id;
  }
}
