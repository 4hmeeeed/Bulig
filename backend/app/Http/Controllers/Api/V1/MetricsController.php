<?php

namespace App\Http\Controllers\Api\V1;

use App\Http\Controllers\Controller;
use App\Services\EvaluationMetricsService;
use Illuminate\Http\JsonResponse;

class MetricsController extends Controller
{
    public function __construct(private readonly EvaluationMetricsService $metrics) {}

    public function evaluation(): JsonResponse
    {
        return response()->json($this->metrics->all());
    }
}
