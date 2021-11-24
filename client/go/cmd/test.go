// Copyright Yahoo. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
// vespa test command
// Author: jonmv

package cmd

import (
	"bytes"
	"encoding/json"
	"fmt"
	"github.com/spf13/cobra"
	"github.com/vespa-engine/vespa/client/go/util"
	"github.com/vespa-engine/vespa/client/go/vespa"
	"io/ioutil"
	"math"
	"net/http"
	"net/url"
	"os"
	"path"
	"path/filepath"
	"strings"
	"time"
)

func init() {
	rootCmd.AddCommand(testCmd)
}

// TODO: add link to test doc at cloud.vespa.ai
var testCmd = &cobra.Command{
	Use:   "test [tests directory or test file]",
	Short: "Run a test suite, or a single test",
	Long: `Run a test suite, or a single test

Runs all JSON test files in the specified directory, or the single JSON
test file specified.

If no directory or file is specified, the working directory is used instead.`,
	Example: `$ vespa test src/test/application/tests/system-test
$ vespa test src/test/application/tests/system-test/feed-and-query.json`,
	Args:              cobra.MaximumNArgs(1),
	DisableAutoGenTag: true,
	Run: func(cmd *cobra.Command, args []string) {
		target := getTarget()
		testPath := "."
		if len(args) > 0 {
			testPath = args[0]
		}
		if count, failed := runTests(testPath, target); len(failed) != 0 {
			fmt.Fprintf(stdout, "\nFailed %d of %d tests:\n", len(failed), count)
			for _, test := range failed {
				fmt.Fprintln(stdout, test)
			}
			exitFunc(3)
		} else if count == 0 {
			fmt.Fprintf(stdout, "Failed to find any tests at %v\n", testPath)
			exitFunc(3)
		} else {
			fmt.Fprintf(stdout, "\n%d tests completed successfully\n", count)
		}
	},
}

func runTests(rootPath string, target vespa.Target) (int, []string) {
	count := 0
	failed := make([]string, 0)
	if stat, err := os.Stat(rootPath); err != nil {
		fatalErr(err, "Failed reading specified test path")
	} else if stat.IsDir() {
		tests, err := ioutil.ReadDir(rootPath) // TODO: Use os.ReadDir when >= 1.16 is required.
		if err != nil {
			fatalErr(err, "Failed reading specified test directory")
		}
		previousFailed := false
		for _, test := range tests {
			if !test.IsDir() && filepath.Ext(test.Name()) == ".json" {
				testPath := path.Join(rootPath, test.Name())
				if previousFailed {
					fmt.Fprintln(stdout, "")
					previousFailed = false
				}
				failure := runTest(testPath, target)
				if failure != "" {
					failed = append(failed, failure)
					previousFailed = true
				}
				count++
			}
		}
	} else if strings.HasSuffix(stat.Name(), ".json") {
		failure := runTest(rootPath, target)
		if failure != "" {
			failed = append(failed, failure)
		}
		count++
	}
	return count, failed
}

// Runs the test at the given path, and returns the specified test name if the test fails
func runTest(testPath string, target vespa.Target) string {
	var test test
	testBytes, err := ioutil.ReadFile(testPath)
	if err != nil {
		fatalErr(err, fmt.Sprintf("Failed to read test file at %s", testPath))
	}
	if err = json.Unmarshal(testBytes, &test); err != nil {
		fatalErr(err, fmt.Sprintf("Failed to parse test file at %s", testPath))
	}

	testName := test.Name
	if test.Name == "" {
		testName = testPath
	}
	fmt.Fprintf(stdout, "Running %s:", testName)

	defaultParameters, err := getParameters(test.Defaults.ParametersRaw, path.Dir(testPath))
	if err != nil {
		fatalErr(err, fmt.Sprintf("Invalid default parameters for %s", testName))
	}

	if len(test.Steps) == 0 {
		fatalErr(fmt.Errorf("a test must have at least one step, but none were found in %s", testPath))
	}
	for i, step := range test.Steps {
		stepName := step.Name
		if stepName == "" {
			stepName = fmt.Sprintf("step %d", i+1)
		}
		failure, longFailure, err := verify(step, path.Dir(testPath), test.Defaults.Cluster, defaultParameters, target)
		if err != nil {
			fatalErr(err, fmt.Sprintf("Error in %s", stepName))
		}
		if failure != "" {
			fmt.Fprintf(stdout, " Failed %s:\n%s\n", stepName, longFailure)
			return fmt.Sprintf("%s: %s: %s", testName, stepName, failure)
		}
		if i == 0 {
			fmt.Fprintf(stdout, " ")
		}
		fmt.Fprint(stdout, ".")
	}
	fmt.Fprintln(stdout, " OK")
	return ""
}

// Asserts specified response is obtained for request, or returns a failure message, or an error if this fails
func verify(step step, testsPath string, defaultCluster string, defaultParameters map[string]string, target vespa.Target) (string, string, error) {
	requestBody, err := getBody(step.Request.BodyRaw, testsPath)
	if err != nil {
		return "", "", err
	}

	parameters, err := getParameters(step.Request.ParametersRaw, testsPath)
	if err != nil {
		return "", "", err
	}
	for name, value := range defaultParameters {
		if _, present := parameters[name]; !present {
			parameters[name] = value
		}
	}

	cluster := step.Request.Cluster
	if cluster == "" {
		cluster = defaultCluster
	}

	service, err := target.Service("query", 0, 0, cluster)
	if err != nil {
		return "", "", err
	}

	method := step.Request.Method
	if method == "" {
		method = "GET"
	}

	pathAndQuery := step.Request.URI
	if pathAndQuery == "" {
		pathAndQuery = "/search/"
	}
	requestUrl, err := url.ParseRequestURI(service.BaseURL + pathAndQuery)
	if err != nil {
		return "", "", err
	}
	query := requestUrl.Query()
	for name, value := range parameters {
		query.Add(name, value)
	}
	requestUrl.RawQuery = query.Encode()

	header := http.Header{}
	header.Add("Content-Type", "application/json") // TODO: Not guaranteed to be true ...

	request := &http.Request{
		URL:    requestUrl,
		Method: method,
		Header: header,
		Body:   ioutil.NopCloser(bytes.NewReader(requestBody)),
	}
	defer request.Body.Close()

	response, err := service.Do(request, 600*time.Second) // Vespa should provide a response within the given request timeout
	if err != nil {
		return "", "", err
	}
	defer response.Body.Close()

	statusCode := step.Response.Code
	if statusCode == 0 {
		statusCode = 200
	}
	if statusCode != response.StatusCode {
		failure := fmt.Sprintf("Unexpected status code: %d", response.StatusCode)
		return failure, fmt.Sprintf("%s\nExpected: %d\nActual response:\n%s", failure, statusCode, util.ReaderToJSON(response.Body)), nil
	}

	responseBodySpecBytes, err := getBody(step.Response.BodyRaw, testsPath)
	if err != nil {
		return "", "", err
	}
	if responseBodySpecBytes == nil {
		return "", "", nil
	}
	var responseBodySpec interface{}
	err = json.Unmarshal(responseBodySpecBytes, &responseBodySpec)
	if err != nil {
		return "", "", err
	}

	responseBodyBytes, err := ioutil.ReadAll(response.Body)
	if err != nil {
		return "", "", err
	}
	var responseBody interface{}
	err = json.Unmarshal(responseBodyBytes, &responseBody)
	if err != nil {
		return "", "", fmt.Errorf("got non-JSON response; %w:\n%s", err, string(responseBodyBytes))
	}

	failure, expected, err := compare(responseBodySpec, responseBody, "")
	if failure != "" {
		responsePretty, _ := json.MarshalIndent(responseBody, "", "  ")
		longFailure := failure
		if expected != "" {
			longFailure += "\n" + expected
		}
		longFailure += "\nActual response:\n" + string(responsePretty)
		return failure, longFailure, err
	}
	return "", "", err
}

func compare(expected interface{}, actual interface{}, path string) (string, string, error) {
	typeMatch := false
	valueMatch := false
	switch u := expected.(type) {
	case nil:
		typeMatch = actual == nil
		valueMatch = actual == nil
	case bool:
		v, ok := actual.(bool)
		typeMatch = ok
		valueMatch = ok && u == v
	case float64:
		v, ok := actual.(float64)
		typeMatch = ok
		valueMatch = ok && math.Abs(u-v) < 1e-9
	case string:
		v, ok := actual.(string)
		typeMatch = ok
		valueMatch = ok && (u == v)
	case []interface{}:
		v, ok := actual.([]interface{})
		typeMatch = ok
		if ok {
			if len(u) == len(v) {
				for i, e := range u {
					failure, expected, err := compare(e, v[i], fmt.Sprintf("%s/%d", path, i))
					if failure != "" || err != nil {
						return failure, expected, err
					}
				}
				valueMatch = true
			} else {
				return fmt.Sprintf("Unexpected number of elements at %s: %d", path, len(v)), fmt.Sprintf("Expected: %d", len(u)), nil
			}
		}
	case map[string]interface{}:
		v, ok := actual.(map[string]interface{})
		typeMatch = ok
		if ok {
			for n, e := range u {
				childPath := fmt.Sprintf("%s/%s", path, strings.ReplaceAll(strings.ReplaceAll(n, "~", "~0"), "/", "~1"))
				f, ok := v[n]
				if !ok {
					return fmt.Sprintf("Missing expected field at %s", childPath), "", nil
				}
				failure, expected, err := compare(e, f, childPath)
				if failure != "" || err != nil {
					return failure, expected, err
				}
			}
			valueMatch = true
		}
	default:
		return "", "", fmt.Errorf("unexpected expected JSON type for value '%v'", expected)
	}

	if !valueMatch {
		if path == "" {
			path = "root"
		}
		mismatched := "type"
		if typeMatch {
			mismatched = "value"
		}
		expectedJson, _ := json.Marshal(expected)
		actualJson, _ := json.Marshal(actual)
		return fmt.Sprintf("Unexpected %s at %s: %s", mismatched, path, actualJson), fmt.Sprintf("Expected: %s", expectedJson), nil
	}
	return "", "", nil
}

func getParameters(parametersRaw []byte, testsPath string) (map[string]string, error) {
	if parametersRaw != nil {
		var parametersPath string
		if err := json.Unmarshal(parametersRaw, &parametersPath); err == nil {
			resolvedParametersPath := path.Join(testsPath, parametersPath)
			parametersRaw, err = ioutil.ReadFile(resolvedParametersPath)
			if err != nil {
				fatalErr(err, fmt.Sprintf("Failed to read request parameters file at '%s'", resolvedParametersPath))
			}
		}
		var parameters map[string]string
		if err := json.Unmarshal(parametersRaw, &parameters); err != nil {
			return nil, fmt.Errorf("request parameters must be JSON with only string values: %w", err)
		}
		return parameters, nil
	}
	return make(map[string]string), nil
}

func getBody(bodyRaw []byte, testsPath string) ([]byte, error) {
	var bodyPath string
	if err := json.Unmarshal(bodyRaw, &bodyPath); err == nil {
		resolvedBodyPath := path.Join(testsPath, bodyPath)
		bodyRaw, err = ioutil.ReadFile(resolvedBodyPath)
		if err != nil {
			fatalErr(err, fmt.Sprintf("Failed to read body file at '%s'", resolvedBodyPath))
		}
	}
	return bodyRaw, nil
}

type test struct {
	Name     string   `json:"name"`
	Defaults defaults `json:"defaults"`
	Steps    []step   `json:"steps"`
}

type defaults struct {
	Cluster       string          `json:"cluster"`
	ParametersRaw json.RawMessage `json:"parameters"`
}

type step struct {
	Name     string   `json:"name"`
	Request  request  `json:"request"`
	Response response `json:"response"`
}

type request struct {
	Cluster       string          `json:"cluster"`
	Method        string          `json:"method"`
	URI           string          `json:"uri"`
	ParametersRaw json.RawMessage `json:"parameters"`
	BodyRaw       json.RawMessage `json:"body"`
}

type response struct {
	Code    int             `json:"code"`
	BodyRaw json.RawMessage `json:"body"`
}
