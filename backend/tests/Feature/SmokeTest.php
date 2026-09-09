<?php

namespace Tests\Feature;

use Tests\TestCase;

class SmokeTest extends TestCase
{
    public function test_the_root_url_leads_to_the_command_center(): void
    {
        // Guests land on the sign-in page; the root is not a public landing page.
        $this->get('/')->assertRedirect('/dashboard');
        $this->get('/dashboard')->assertRedirect('/login');
        $this->get('/login')->assertOk()->assertSee('Barangay Command Center');
    }

    public function test_the_prototype_disclaimer_is_shown_on_the_sign_in_page(): void
    {
        // Section 22: the system must state plainly that it is a capstone
        // prototype and does not replace official emergency services.
        $this->get('/login')->assertSee('does not replace official emergency services');
    }
}
