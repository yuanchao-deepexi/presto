/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.facebook.presto.metadata;

import com.facebook.presto.Session;
import com.facebook.presto.operator.aggregation.InternalAggregationFunction;
import com.facebook.presto.operator.scalar.ScalarFunctionImplementation;
import com.facebook.presto.operator.window.WindowFunctionSupplier;
import com.facebook.presto.spi.PrestoException;
import com.facebook.presto.spi.block.BlockEncodingSerde;
import com.facebook.presto.spi.function.FunctionHandle;
import com.facebook.presto.spi.function.FunctionKind;
import com.facebook.presto.spi.function.FunctionMetadata;
import com.facebook.presto.spi.function.FunctionMetadataManager;
import com.facebook.presto.spi.function.OperatorType;
import com.facebook.presto.spi.function.Signature;
import com.facebook.presto.spi.function.SqlFunction;
import com.facebook.presto.spi.relation.FullyQualifiedName;
import com.facebook.presto.spi.type.Type;
import com.facebook.presto.spi.type.TypeManager;
import com.facebook.presto.spi.type.TypeSignature;
import com.facebook.presto.sql.analyzer.FeaturesConfig;
import com.facebook.presto.sql.analyzer.TypeSignatureProvider;
import com.facebook.presto.sql.tree.QualifiedName;
import com.facebook.presto.type.TypeRegistry;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Ordering;

import javax.annotation.concurrent.ThreadSafe;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static com.facebook.presto.metadata.BuiltInFunctionNamespaceManager.DEFAULT_NAMESPACE;
import static com.facebook.presto.metadata.CastType.toOperatorType;
import static com.facebook.presto.spi.StandardErrorCode.AMBIGUOUS_FUNCTION_CALL;
import static com.facebook.presto.spi.StandardErrorCode.FUNCTION_IMPLEMENTATION_MISSING;
import static com.facebook.presto.spi.StandardErrorCode.FUNCTION_NOT_FOUND;
import static com.facebook.presto.spi.function.FunctionKind.SCALAR;
import static com.facebook.presto.spi.type.TypeSignature.parseTypeSignature;
import static com.facebook.presto.sql.analyzer.TypeSignatureProvider.fromTypeSignatures;
import static com.facebook.presto.sql.planner.LiteralEncoder.MAGIC_LITERAL_FUNCTION_PREFIX;
import static com.facebook.presto.sql.planner.LiteralEncoder.getMagicLiteralFunctionSignature;
import static com.facebook.presto.type.TypeUtils.resolveTypes;
import static com.facebook.presto.type.UnknownType.UNKNOWN;
import static com.google.common.base.MoreObjects.toStringHelper;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static com.google.common.collect.Iterables.getOnlyElement;
import static java.lang.String.format;
import static java.util.Collections.emptyList;
import static java.util.Collections.singletonList;
import static java.util.Objects.requireNonNull;

@ThreadSafe
public class FunctionManager
        implements FunctionMetadataManager
{
    private final TypeManager typeManager;
    private final BuiltInFunctionNamespaceManager builtInFunctionNamespace;
    private final FunctionInvokerProvider functionInvokerProvider;

    public FunctionManager(TypeManager typeManager, BlockEncodingSerde blockEncodingSerde, FeaturesConfig featuresConfig)
    {
        this.typeManager = requireNonNull(typeManager, "typeManager is null");
        this.builtInFunctionNamespace = new BuiltInFunctionNamespaceManager(typeManager, blockEncodingSerde, featuresConfig, this);
        this.functionInvokerProvider = new FunctionInvokerProvider(this);
        if (typeManager instanceof TypeRegistry) {
            ((TypeRegistry) typeManager).setFunctionManager(this);
        }
    }

    public FunctionInvokerProvider getFunctionInvokerProvider()
    {
        return functionInvokerProvider;
    }

    public void addFunctions(List<? extends SqlFunction> functions)
    {
        builtInFunctionNamespace.addFunctions(functions);
    }

    public List<SqlFunction> listFunctions()
    {
        return builtInFunctionNamespace.listFunctions().stream()
                .filter(function -> !function.isHidden())
                .collect(toImmutableList());
    }

    /**
     * Resolves a function using the SQL path, and implicit type coercions.
     *
     * @throws PrestoException if there are no matches or multiple matches
     */
    public FunctionHandle resolveFunction(Session session, QualifiedName name, List<TypeSignatureProvider> parameterTypes)
    {
        // TODO Actually use session
        // Session will be used to provide information about the order of function namespaces to through resolving the function.
        // This is likely to be in terms of SQL path. Currently we still don't have support multiple function namespaces, nor
        // SQL path. As a result, session is not used here. We still add this to distinguish the two versions of resolveFunction
        // while the refactoring is on-going.
        try {
            return lookupFunction(name.getSuffix(), parameterTypes);
        }
        catch (PrestoException e) {
            if (e.getErrorCode().getCode() != FUNCTION_NOT_FOUND.toErrorCode().getCode()) {
                throw e;
            }
        }
        FullyQualifiedName fullyQualifiedName = FullyQualifiedName.of(DEFAULT_NAMESPACE, name.getSuffix());
        Collection<SqlFunction> allCandidates = builtInFunctionNamespace.getCandidates(null, fullyQualifiedName);
        Optional<Signature> match = matchFunctionWithCoercion(allCandidates, parameterTypes);
        if (match.isPresent()) {
            return new BuiltInFunctionHandle(match.get());
        }

        if (name.getSuffix().startsWith(MAGIC_LITERAL_FUNCTION_PREFIX)) {
            // extract type from function name
            String typeName = name.getSuffix().substring(MAGIC_LITERAL_FUNCTION_PREFIX.length());

            // lookup the type
            Type type = typeManager.getType(parseTypeSignature(typeName));

            // verify we have one parameter of the proper type
            checkArgument(parameterTypes.size() == 1, "Expected one argument to literal function, but got %s", parameterTypes);

            return new BuiltInFunctionHandle(getMagicLiteralFunctionSignature(type));
        }

        throw new PrestoException(FUNCTION_NOT_FOUND, constructFunctionNotFoundErrorMessage(name.getSuffix(), parameterTypes, allCandidates));
    }

    @Override
    public FunctionMetadata getFunctionMetadata(FunctionHandle functionHandle)
    {
        return builtInFunctionNamespace.getFunctionMetadata(functionHandle);
    }

    public WindowFunctionSupplier getWindowFunctionImplementation(FunctionHandle functionHandle)
    {
        return builtInFunctionNamespace.getWindowFunctionImplementation(functionHandle);
    }

    public InternalAggregationFunction getAggregateFunctionImplementation(FunctionHandle functionHandle)
    {
        return builtInFunctionNamespace.getAggregateFunctionImplementation(functionHandle);
    }

    public ScalarFunctionImplementation getScalarFunctionImplementation(FunctionHandle functionHandle)
    {
        return builtInFunctionNamespace.getScalarFunctionImplementation(functionHandle);
    }

    @VisibleForTesting
    public List<SqlFunction> listOperators()
    {
        Set<FullyQualifiedName> operatorNames = Arrays.asList(OperatorType.values()).stream()
                .map(OperatorType::getFunctionName)
                .collect(toImmutableSet());

        return builtInFunctionNamespace.listFunctions().stream()
                .filter(function -> operatorNames.contains(function.getSignature().getName()))
                .collect(toImmutableList());
    }

    public FunctionHandle resolveOperator(OperatorType operatorType, List<TypeSignatureProvider> argumentTypes)
    {
        try {
            return resolveFunction(null, QualifiedName.of(operatorType.getFunctionName().getParts()), argumentTypes);
        }
        catch (PrestoException e) {
            if (e.getErrorCode().getCode() == FUNCTION_NOT_FOUND.toErrorCode().getCode()) {
                throw new OperatorNotFoundException(
                        operatorType,
                        argumentTypes.stream()
                                .map(TypeSignatureProvider::getTypeSignature)
                                .collect(toImmutableList()));
            }
            else {
                throw e;
            }
        }
    }

    /**
     * Lookup up a function with name and fully bound types. This can only be used for builtin functions. {@link #resolveFunction(Session, QualifiedName, List)}
     * should be used for dynamically registered functions.
     *
     * @throws PrestoException if function could not be found
     */
    public FunctionHandle lookupFunction(String name, List<TypeSignatureProvider> parameterTypes)
    {
        FullyQualifiedName fullyQualifiedName = FullyQualifiedName.of(DEFAULT_NAMESPACE, name);
        Collection<SqlFunction> allCandidates = builtInFunctionNamespace.getCandidates(null, fullyQualifiedName);
        List<SqlFunction> exactCandidates = allCandidates.stream()
                .filter(function -> function.getSignature().getTypeVariableConstraints().isEmpty())
                .collect(Collectors.toList());

        Optional<Signature> match = matchFunctionExact(exactCandidates, parameterTypes);
        if (match.isPresent()) {
            return new BuiltInFunctionHandle(match.get());
        }

        List<SqlFunction> genericCandidates = allCandidates.stream()
                .filter(function -> !function.getSignature().getTypeVariableConstraints().isEmpty())
                .collect(Collectors.toList());

        match = matchFunctionExact(genericCandidates, parameterTypes);
        if (match.isPresent()) {
            return new BuiltInFunctionHandle(match.get());
        }

        throw new PrestoException(FUNCTION_NOT_FOUND, constructFunctionNotFoundErrorMessage(name, parameterTypes, allCandidates));
    }

    public FunctionHandle lookupCast(CastType castType, TypeSignature fromType, TypeSignature toType)
    {
        Signature signature = new Signature(castType.getCastName(), SCALAR, emptyList(), emptyList(), toType, singletonList(fromType), false);

        try {
            builtInFunctionNamespace.getScalarFunctionImplementation(signature);
        }
        catch (PrestoException e) {
            if (castType.isOperatorType() && e.getErrorCode().getCode() == FUNCTION_IMPLEMENTATION_MISSING.toErrorCode().getCode()) {
                throw new OperatorNotFoundException(toOperatorType(castType), ImmutableList.of(fromType), toType);
            }
            throw e;
        }
        return builtInFunctionNamespace.getFunctionHandle(null, signature);
    }

    private String constructFunctionNotFoundErrorMessage(String name, List<TypeSignatureProvider> parameterTypes, Collection<SqlFunction> candidates)
    {
        List<String> expectedParameters = new ArrayList<>();
        for (SqlFunction function : candidates) {
            expectedParameters.add(format("%s(%s) %s",
                    name,
                    Joiner.on(", ").join(function.getSignature().getArgumentTypes()),
                    Joiner.on(", ").join(function.getSignature().getTypeVariableConstraints())));
        }
        String parameters = Joiner.on(", ").join(parameterTypes);
        String message = format("Function %s not registered", name);
        if (!expectedParameters.isEmpty()) {
            String expected = Joiner.on(", ").join(expectedParameters);
            message = format("Unexpected parameters (%s) for function %s. Expected: %s", parameters, name, expected);
        }
        return message;
    }

    private Optional<Signature> matchFunctionExact(List<SqlFunction> candidates, List<TypeSignatureProvider> actualParameters)
    {
        return matchFunction(candidates, actualParameters, false);
    }

    private Optional<Signature> matchFunctionWithCoercion(Collection<SqlFunction> candidates, List<TypeSignatureProvider> actualParameters)
    {
        return matchFunction(candidates, actualParameters, true);
    }

    private Optional<Signature> matchFunction(Collection<SqlFunction> candidates, List<TypeSignatureProvider> parameters, boolean coercionAllowed)
    {
        List<ApplicableFunction> applicableFunctions = identifyApplicableFunctions(candidates, parameters, coercionAllowed);
        if (applicableFunctions.isEmpty()) {
            return Optional.empty();
        }

        if (coercionAllowed) {
            applicableFunctions = selectMostSpecificFunctions(applicableFunctions, parameters);
            checkState(!applicableFunctions.isEmpty(), "at least single function must be left");
        }

        if (applicableFunctions.size() == 1) {
            return Optional.of(getOnlyElement(applicableFunctions).getBoundSignature());
        }

        StringBuilder errorMessageBuilder = new StringBuilder();
        errorMessageBuilder.append("Could not choose a best candidate operator. Explicit type casts must be added.\n");
        errorMessageBuilder.append("Candidates are:\n");
        for (ApplicableFunction function : applicableFunctions) {
            errorMessageBuilder.append("\t * ");
            errorMessageBuilder.append(function.getBoundSignature().toString());
            errorMessageBuilder.append("\n");
        }
        throw new PrestoException(AMBIGUOUS_FUNCTION_CALL, errorMessageBuilder.toString());
    }

    private List<ApplicableFunction> identifyApplicableFunctions(Collection<SqlFunction> candidates, List<TypeSignatureProvider> actualParameters, boolean allowCoercion)
    {
        ImmutableList.Builder<ApplicableFunction> applicableFunctions = ImmutableList.builder();
        for (SqlFunction function : candidates) {
            Signature declaredSignature = function.getSignature();
            Optional<Signature> boundSignature = new SignatureBinder(typeManager, declaredSignature, allowCoercion)
                    .bind(actualParameters);
            if (boundSignature.isPresent()) {
                applicableFunctions.add(new ApplicableFunction(declaredSignature, boundSignature.get()));
            }
        }
        return applicableFunctions.build();
    }

    private List<ApplicableFunction> selectMostSpecificFunctions(List<ApplicableFunction> applicableFunctions, List<TypeSignatureProvider> parameters)
    {
        checkArgument(!applicableFunctions.isEmpty());

        List<ApplicableFunction> mostSpecificFunctions = selectMostSpecificFunctions(applicableFunctions);
        if (mostSpecificFunctions.size() <= 1) {
            return mostSpecificFunctions;
        }

        Optional<List<Type>> optionalParameterTypes = toTypes(parameters, typeManager);
        if (!optionalParameterTypes.isPresent()) {
            // give up and return all remaining matches
            return mostSpecificFunctions;
        }

        List<Type> parameterTypes = optionalParameterTypes.get();
        if (!someParameterIsUnknown(parameterTypes)) {
            // give up and return all remaining matches
            return mostSpecificFunctions;
        }

        // look for functions that only cast the unknown arguments
        List<ApplicableFunction> unknownOnlyCastFunctions = getUnknownOnlyCastFunctions(applicableFunctions, parameterTypes);
        if (!unknownOnlyCastFunctions.isEmpty()) {
            mostSpecificFunctions = unknownOnlyCastFunctions;
            if (mostSpecificFunctions.size() == 1) {
                return mostSpecificFunctions;
            }
        }

        // If the return type for all the selected function is the same, and the parameters are declared as RETURN_NULL_ON_NULL
        // all the functions are semantically the same. We can return just any of those.
        if (returnTypeIsTheSame(mostSpecificFunctions) && allReturnNullOnGivenInputTypes(mostSpecificFunctions, parameterTypes)) {
            // make it deterministic
            ApplicableFunction selectedFunction = Ordering.usingToString()
                    .reverse()
                    .sortedCopy(mostSpecificFunctions)
                    .get(0);
            return ImmutableList.of(selectedFunction);
        }

        return mostSpecificFunctions;
    }

    private List<ApplicableFunction> selectMostSpecificFunctions(List<ApplicableFunction> candidates)
    {
        List<ApplicableFunction> representatives = new ArrayList<>();

        for (ApplicableFunction current : candidates) {
            boolean found = false;
            for (int i = 0; i < representatives.size(); i++) {
                ApplicableFunction representative = representatives.get(i);
                if (isMoreSpecificThan(current, representative)) {
                    representatives.set(i, current);
                }
                if (isMoreSpecificThan(current, representative) || isMoreSpecificThan(representative, current)) {
                    found = true;
                    break;
                }
            }

            if (!found) {
                representatives.add(current);
            }
        }

        return representatives;
    }

    private static boolean someParameterIsUnknown(List<Type> parameters)
    {
        return parameters.stream().anyMatch(type -> type.equals(UNKNOWN));
    }

    private List<ApplicableFunction> getUnknownOnlyCastFunctions(List<ApplicableFunction> applicableFunction, List<Type> actualParameters)
    {
        return applicableFunction.stream()
                .filter((function) -> onlyCastsUnknown(function, actualParameters))
                .collect(toImmutableList());
    }

    private boolean onlyCastsUnknown(ApplicableFunction applicableFunction, List<Type> actualParameters)
    {
        List<Type> boundTypes = resolveTypes(applicableFunction.getBoundSignature().getArgumentTypes(), typeManager);
        checkState(actualParameters.size() == boundTypes.size(), "type lists are of different lengths");
        for (int i = 0; i < actualParameters.size(); i++) {
            if (!boundTypes.get(i).equals(actualParameters.get(i)) && actualParameters.get(i) != UNKNOWN) {
                return false;
            }
        }
        return true;
    }

    private boolean returnTypeIsTheSame(List<ApplicableFunction> applicableFunctions)
    {
        Set<Type> returnTypes = applicableFunctions.stream()
                .map(function -> typeManager.getType(function.getBoundSignature().getReturnType()))
                .collect(Collectors.toSet());
        return returnTypes.size() == 1;
    }

    private boolean allReturnNullOnGivenInputTypes(List<ApplicableFunction> applicableFunctions, List<Type> parameters)
    {
        return applicableFunctions.stream().allMatch(x -> returnsNullOnGivenInputTypes(x, parameters));
    }

    private boolean returnsNullOnGivenInputTypes(ApplicableFunction applicableFunction, List<Type> parameterTypes)
    {
        Signature boundSignature = applicableFunction.getBoundSignature();
        FunctionKind functionKind = boundSignature.getKind();
        // Window and Aggregation functions have fixed semantic where NULL values are always skipped
        if (functionKind != SCALAR) {
            return true;
        }

        for (int i = 0; i < parameterTypes.size(); i++) {
            Type parameterType = parameterTypes.get(i);
            if (parameterType.equals(UNKNOWN)) {
                // TODO: This still doesn't feel right. Need to understand function resolution logic better to know what's the right way.
                BuiltInFunctionHandle functionHandle = new BuiltInFunctionHandle(boundSignature);
                if (getFunctionMetadata(functionHandle).isCalledOnNullInput()) {
                    return false;
                }
            }
        }
        return true;
    }

    private static Optional<List<Type>> toTypes(List<TypeSignatureProvider> typeSignatureProviders, TypeManager typeManager)
    {
        ImmutableList.Builder<Type> resultBuilder = ImmutableList.builder();
        for (TypeSignatureProvider typeSignatureProvider : typeSignatureProviders) {
            if (typeSignatureProvider.hasDependency()) {
                return Optional.empty();
            }
            resultBuilder.add(typeManager.getType(typeSignatureProvider.getTypeSignature()));
        }
        return Optional.of(resultBuilder.build());
    }

    /**
     * One method is more specific than another if invocation handled by the first method could be passed on to the other one
     */
    private boolean isMoreSpecificThan(ApplicableFunction left, ApplicableFunction right)
    {
        List<TypeSignatureProvider> resolvedTypes = fromTypeSignatures(left.getBoundSignature().getArgumentTypes());
        Optional<BoundVariables> boundVariables = new SignatureBinder(typeManager, right.getDeclaredSignature(), true)
                .bindVariables(resolvedTypes);
        return boundVariables.isPresent();
    }

    private static class ApplicableFunction
    {
        private final Signature declaredSignature;
        private final Signature boundSignature;

        private ApplicableFunction(Signature declaredSignature, Signature boundSignature)
        {
            this.declaredSignature = declaredSignature;
            this.boundSignature = boundSignature;
        }

        public Signature getDeclaredSignature()
        {
            return declaredSignature;
        }

        public Signature getBoundSignature()
        {
            return boundSignature;
        }

        @Override
        public String toString()
        {
            return toStringHelper(this)
                    .add("declaredSignature", declaredSignature)
                    .add("boundSignature", boundSignature)
                    .toString();
        }
    }
}
